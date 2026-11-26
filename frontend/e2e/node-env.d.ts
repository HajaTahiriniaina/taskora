// Seule API Node utilisée par les tests et la configuration Playwright : process.env.
// (@types/node n'est pas une dépendance du projet ; frontend.md §5.1 fixe la liste.)
declare const process: { env: Record<string, string | undefined> };
