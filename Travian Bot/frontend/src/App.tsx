import { Navigate, Route, Routes } from 'react-router-dom';
import AppLayout from './components/AppLayout';
import { AccountsProvider } from './features/accounts/AccountsContext';
import { GeneralSettingsProvider } from './features/settings/GeneralSettingsContext';
import { EventStreamProvider } from './features/events/EventStreamContext';
import AccountsPage from './pages/AccountsPage';
import AccountDetailPage from './pages/AccountDetailPage';
import SettingsPage from './pages/SettingsPage';
import VillagesListPage from './pages/VillagesListPage';
import NotesListPage from './pages/NotesListPage';
import NoteEditorPage from './pages/NoteEditorPage';
import FarmlistsListPage from './pages/FarmlistsListPage';
import FarmlistDetailPage from './pages/FarmlistDetailPage';
import StatisticsPage from './pages/StatisticsPage';
import TasksListPage from './pages/TasksListPage';
import HeroPage from './pages/HeroPage';
import BotLogPage from './pages/BotLogPage';
import TemplatesPage from './pages/TemplatesPage';
import AiAnalysisPage from './pages/AiAnalysisPage';
import IncomingAttacksPage from './pages/IncomingAttacksPage';
import InactiveSearchPage from './pages/InactiveSearchPage';
import EnemyIntelPage from './pages/EnemyIntelPage';
import CroppersPage from './pages/CroppersPage';
import AlliancesPage from './pages/AlliancesPage';
import PlayersPage from './pages/PlayersPage';
import ReportIncomingAttacksPage from './pages/ReportIncomingAttacksPage';
import TroopCountsPage from './pages/TroopCountsPage';
import AttackPlannerPage from './pages/AttackPlannerPage';
import TravelCalculatorPage from './pages/TravelCalculatorPage';
import MapPage from './pages/MapPage';
import CommandCenterPage from './pages/CommandCenterPage';
import DiscordPage from './pages/DiscordPage';
import TelegramPage from './pages/TelegramPage';

export default function App() {
  return (
    <GeneralSettingsProvider>
      <AccountsProvider>
        <EventStreamProvider>
        <AppLayout>
          <Routes>
            <Route path="/" element={<CommandCenterPage />} />
            <Route path="/accounts" element={<AccountsPage />} />
            <Route path="/accounts/:slug" element={<AccountDetailPage />} />
            <Route path="/settings" element={<SettingsPage />} />

            <Route path="/villages" element={<VillagesListPage />} />
            <Route path="/villages/:id" element={<VillagesListPage />} />

            <Route path="/notes" element={<NotesListPage />} />
            <Route path="/notes/new" element={<NoteEditorPage />} />
            <Route path="/notes/:id" element={<NoteEditorPage />} />

            <Route path="/farmlists" element={<FarmlistsListPage />} />
            <Route path="/farmlists/:id" element={<FarmlistDetailPage />} />

            <Route path="/statistics" element={<StatisticsPage />} />

            <Route path="/tasks" element={<TasksListPage />} />

            <Route path="/hero" element={<HeroPage />} />
            <Route path="/log" element={<BotLogPage />} />
            <Route path="/templates" element={<TemplatesPage />} />
            <Route path="/ai-analysis" element={<AiAnalysisPage />} />
            <Route path="/incoming" element={<IncomingAttacksPage />} />
            <Route path="/map" element={<MapPage />} />
            <Route path="/intel" element={<InactiveSearchPage />} />
            <Route path="/enemy-intel" element={<EnemyIntelPage />} />
            <Route path="/croppers" element={<CroppersPage />} />
            <Route path="/alliances" element={<AlliancesPage />} />
            <Route path="/players" element={<PlayersPage />} />
            <Route path="/defense/report-incoming" element={<ReportIncomingAttacksPage />} />
            <Route path="/troops/counts" element={<TroopCountsPage />} />
            <Route path="/offense/planner" element={<AttackPlannerPage />} />
            <Route path="/calculators/travel" element={<TravelCalculatorPage />} />
            <Route path="/integrations/discord" element={<DiscordPage />} />
            <Route path="/integrations/telegram" element={<TelegramPage />} />

            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </AppLayout>
        </EventStreamProvider>
      </AccountsProvider>
    </GeneralSettingsProvider>
  );
}
