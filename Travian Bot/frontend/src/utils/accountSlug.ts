import type { Account } from '../types/account';

/**
 * Turns any human string into a URL-safe lowercase slug.
 * <p>
 * The rules are deliberately simple — we don't care about Unicode preservation
 * or collision resistance because the slug is paired with the numeric account
 * ID via {@link findAccountBySlug} lookups. NFKD + strip combining marks
 * catches the accented Latin characters Travian servers are in, and
 * everything non-alphanumeric collapses to a single dash.
 */
export function slugify(input: string): string {
  return input
    .normalize('NFKD')
    // eslint-disable-next-line no-misleading-character-class
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
}

/**
 * Derives the server-hostname portion of the slug. Strips the scheme, port,
 * and any path, then runs through {@link slugify}. For
 * {@code https://ts1.x1.europe.travian.com/dorf1.php} this yields
 * {@code ts1-x1-europe-travian-com}.
 */
function hostnameSlug(serverUrl: string): string {
  try {
    return slugify(new URL(serverUrl).hostname);
  } catch {
    // Malformed URL — fall back to slugifying the whole string so the caller
    // still gets a non-empty slug (the navigator will just 404, which is fine).
    return slugify(serverUrl);
  }
}

/**
 * Canonical route slug for an account: {@code nickname-hostname}. The
 * nickname is guaranteed non-blank by the backend {@code @NotBlank} so we
 * never produce a leading-dash slug.
 */
export function accountSlug(account: Pick<Account, 'nickname' | 'serverUrl'>): string {
  return `${slugify(account.nickname)}-${hostnameSlug(account.serverUrl)}`;
}

/**
 * Reverse lookup used by the detail page. Walks the accounts list computing
 * each slug on demand — the list is small (user's own accounts, typically
 * <20) so there's no index to maintain. Returns {@code undefined} when the
 * slug doesn't match any account, which the page surfaces as "not found".
 */
export function findAccountBySlug(
  accounts: ReadonlyArray<Account>,
  slug: string
): Account | undefined {
  return accounts.find((a) => accountSlug(a) === slug);
}
