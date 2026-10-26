import Card from '../components/Card';
import GlassHeader from '../components/GlassHeader';
import GeneralSettingsForm from '../features/settings/GeneralSettingsForm';

export default function SettingsPage() {
  return (
    <div className="stack">
      <div>
        <GlassHeader
        title="Postavke"
        subtitle="Tema, humanizacija, scraping."
      />
      </div>

      <Card title="General">
        <GeneralSettingsForm />
      </Card>
    </div>
  );
}
