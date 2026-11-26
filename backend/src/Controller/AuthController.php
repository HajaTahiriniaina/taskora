<?php

namespace App\Controller;

use App\Entity\User;
use App\Repository\UserRepository;
use App\Service\InvitationClaimer;
use Lexik\Bundle\JWTAuthenticationBundle\Services\JWTTokenManagerInterface;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;
use Symfony\Component\HttpKernel\Exception\ConflictHttpException;
use Symfony\Component\PasswordHasher\Hasher\UserPasswordHasherInterface;
use Symfony\Component\Routing\Attribute\Route;

#[Route('/api')]
class AuthController extends ApiController
{
    #[Route('/auth/register', methods: ['POST'])]
    public function register(Request $request, UserRepository $users, UserPasswordHasherInterface $hasher, JWTTokenManagerInterface $jwt, InvitationClaimer $claimer): JsonResponse
    {
        $data = $this->body($request);
        $name = $this->requireString($data, 'name', 120);
        $email = mb_strtolower($this->requireString($data, 'email', 180));
        $password = (string) ($data['password'] ?? '');

        if (!filter_var($email, FILTER_VALIDATE_EMAIL)) {
            throw new BadRequestHttpException('Adresse e-mail invalide.');
        }
        if (mb_strlen($password) < 8) {
            throw new BadRequestHttpException('Le mot de passe doit contenir au moins 8 caractères.');
        }
        if ($users->findOneByEmail($email)) {
            throw new ConflictHttpException('Un compte existe déjà avec cette adresse e-mail.');
        }

        $user = (new User())->setName($name)->setEmail($email);
        $user->setPassword($hasher->hashPassword($user, $password));
        $this->em->persist($user);

        // Turn pending invitations into memberships
        $claimer->claim($user, $email);
        $this->em->flush();

        return $this->json(['token' => $jwt->create($user), 'user' => $this->presenter->user($user)], 201);
    }

    #[Route('/me', methods: ['GET'])]
    public function profile(): JsonResponse
    {
        return $this->json($this->presenter->user($this->me()));
    }

    #[Route('/me', methods: ['PATCH'])]
    public function updateMe(Request $request, UserPasswordHasherInterface $hasher): JsonResponse
    {
        $data = $this->body($request);
        $me = $this->me();
        if (isset($data['name'])) {
            $me->setName($this->requireString($data, 'name', 120));
        }
        if (isset($data['avatarColor']) && preg_match('/^#[0-9a-fA-F]{6}$/', $data['avatarColor'])) {
            $me->setAvatarColor($data['avatarColor']);
        }
        if (!empty($data['newPassword'])) {
            if (!$hasher->isPasswordValid($me, (string) ($data['currentPassword'] ?? ''))) {
                throw new BadRequestHttpException('Mot de passe actuel incorrect.');
            }
            if (mb_strlen($data['newPassword']) < 8) {
                throw new BadRequestHttpException('Le mot de passe doit contenir au moins 8 caractères.');
            }
            $me->setPassword($hasher->hashPassword($me, $data['newPassword']));
        }
        $this->em->flush();
        return $this->json($this->presenter->user($me));
    }
}
