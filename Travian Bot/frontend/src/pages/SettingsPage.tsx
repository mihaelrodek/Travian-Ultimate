import Card from '../components/Card';
import GeneralSettingsForm from '../features/settings/GeneralSettingsForm';

export default function SettingsPage() {
  return (
    <div className="stack">
      <div>
        <h1 className="page__title">Settings</h1>
        <p className="page__subtitle">App-wide preferences. Per-account settings live on each account's page.</p>
      </div>

      <Card title="General">
        <GeneralSettingsForm />
      </Card>
    </div>
  );
}
