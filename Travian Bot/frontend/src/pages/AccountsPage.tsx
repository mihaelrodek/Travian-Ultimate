import Card from '../components/Card';
import AddAccountForm from '../features/accounts/AddAccountForm';
import AccountList from '../features/accounts/AccountList';

/**
 * Accounts landing.
 *
 * Title + subtitle were removed on purpose — the sidebar already tells the
 * user they're on the Accounts page, and the page is short enough that a
 * top-level heading added pure chrome without information. The Add/Saved
 * cards are self-describing.
 *
 * The {@code stack--wide} modifier widens the layout past the default
 * 880px cap so the (otherwise sparse) table can breathe on a normal-sized
 * monitor — the two cards have a lot of visual whitespace at 880px.
 * {@code FloatingQueueBar} is also hidden on this route via its own
 * pathname check; the queue isn't useful context while the user is
 * managing accounts.
 */
export default function AccountsPage() {
  return (
    <div className="stack stack--wide">
      <Card title="Add account">
        <AddAccountForm />
      </Card>

      <Card title="Saved accounts">
        <AccountList />
      </Card>
    </div>
  );
}
