import { Navigate, Route, Routes } from 'react-router-dom';
import AppLayout from './components/AppLayout';
import { AccountsProvider } from './features/accounts/AccountsContext';
import { GeneralSettingsProvider } from './features/settings/GeneralSettingsContext';
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

export default function App() {
  return (
    <GeneralSettingsProvider>
      <AccountsProvider>
        <AppLayout>
          <Routes>
            <Route path="/" element={<Navigate to="/accounts" replace />} />
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

            <Route path="*" element={<Navigate to="/accounts" replace />} />
          </Routes>
        </AppLayout>
      </AccountsProvider>
    </GeneralSettingsProvider>
  );
}
