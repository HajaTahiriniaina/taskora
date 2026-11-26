<?php

namespace App\Command;

use App\Entity\Board;
use App\Entity\BoardList;
use App\Entity\BoardMember;
use App\Entity\CustomField;
use App\Entity\Label;
use App\Repository\UserRepository;
use Doctrine\ORM\EntityManagerInterface;
use Symfony\Component\Console\Attribute\Argument;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Attribute\Option;
use Symfony\Component\Console\Style\SymfonyStyle;

/** Creates a large demo board to test performance: bin/console app:seed-demo you@mail.com --lists=12 --cards=6000 */
#[AsCommand(name: 'app:seed-demo', description: 'Crée un tableau volumineux pour tester les performances')]
class SeedDemoCommand
{
    public function __construct(private EntityManagerInterface $em, private UserRepository $users) {}

    public function __invoke(
        SymfonyStyle $io,
        #[Argument('E-mail du propriétaire (compte existant)')] string $email,
        #[Option('Nombre de listes')] int $lists = 12,
        #[Option('Nombre total de cartes')] int $cards = 5000,
    ): int {
        $owner = $this->users->findOneByEmail($email);
        if (!$owner) {
            $io->error("Aucun compte pour $email");
            return 1;
        }
        $board = new Board($owner, sprintf('Démo charge (%d cartes)', $cards));
        $board->setBackground('midnight');
        $board->addMember(new BoardMember($board, $owner, BoardMember::ROLE_OWNER));
        $this->em->persist($board);
        $labels = [];
        foreach ([['Urgent', '#ef4444'], ['Bug', '#f97316'], ['Feature', '#6366f1'], ['Design', '#ec4899']] as [$n, $c]) {
            $this->em->persist($labels[] = new Label($board, $n, $c));
        }
        $field = new CustomField($board, 'Estimation', 'number', 0);
        $this->em->persist($field);
        $listEntities = [];
        for ($i = 0; $i < $lists; ++$i) {
            $this->em->persist($listEntities[] = new BoardList($board, 'Colonne '.($i + 1), $i));
        }
        $this->em->flush();

        $conn = $this->em->getConnection();
        $now = (new \DateTimeImmutable())->format('Y-m-d H:i:s');
        $prios = ['none', 'low', 'medium', 'high', 'urgent'];
        $perList = intdiv($cards, $lists);
        $number = 0;
        $io->progressStart($cards);
        $conn->beginTransaction();
        foreach ($listEntities as $li => $list) {
            $count = $li === $lists - 1 ? $cards - $perList * ($lists - 1) : $perList;
            for ($p = 0; $p < $count; $p += 500) {
                $rows = [];
                $params = [];
                for ($k = $p; $k < min($count, $p + 500); ++$k) {
                    $rows[] = '(?,?,?,?,?,?,?,?,?,?,?)';
                    array_push($params, $board->getId(), ++$number, $list->getId(), "Tâche #$k · {$list->getTitle()}", 0 === $k % 3 ? 'Description de démonstration' : null,
                        $k, 0 === $k % 4 ? $now : null, 0 === $k % 7 ? 1 : 0, $prios[$k % 5], $owner->getId(), $now);
                }
                $conn->executeStatement('INSERT INTO card (board_id,number,list_id,title,description,position,due_date,completed,priority,created_by_id,created_at) VALUES '.implode(',', $rows), $params);
                $io->progressAdvance(count($rows));
            }
        }
        // labels, assignees and custom values on a subset of cards
        $bid = $board->getId();
        $conn->executeStatement("INSERT INTO card_label (card_id,label_id) SELECT c.id, ? FROM card c JOIN board_list l ON l.id=c.list_id WHERE l.board_id=? AND c.position % 3 = 0", [$labels[2]->getId(), $bid]);
        $conn->executeStatement("INSERT INTO card_label (card_id,label_id) SELECT c.id, ? FROM card c JOIN board_list l ON l.id=c.list_id WHERE l.board_id=? AND c.position % 5 = 0", [$labels[0]->getId(), $bid]);
        $conn->executeStatement("INSERT INTO card_assignee (card_id,user_id) SELECT c.id, ? FROM card c JOIN board_list l ON l.id=c.list_id WHERE l.board_id=? AND c.position % 2 = 0", [$owner->getId(), $bid]);
        $conn->executeStatement("INSERT INTO custom_field_value (card_id,field_id,value) SELECT c.id, ?, CAST(c.position % 13 AS CHAR) FROM card c JOIN board_list l ON l.id=c.list_id WHERE l.board_id=? AND c.position % 4 = 0", [$field->getId(), $bid]);
        $conn->executeStatement('UPDATE board SET card_seq = ? WHERE id = ?', [$number, $board->getId()]);
        $conn->commit();
        $io->progressFinish();
        $io->success(sprintf('Tableau #%d créé : %d listes, %d cartes.', $bid, $lists, $cards));
        return 0;
    }
}
