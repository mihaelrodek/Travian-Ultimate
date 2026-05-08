import { api } from './client';
import type { Note, NoteCreate, NoteUpdate } from '../types/note';

export const notesApi = {
  list: (accountId?: number) =>
    api.get<Note[]>(accountId != null ? `/notes?accountId=${accountId}` : '/notes'),
  get: (id: number) => api.get<Note>(`/notes/${id}`),
  create: (body: NoteCreate) => api.post<Note>('/notes', body),
  update: (id: number, body: NoteUpdate) => api.put<Note>(`/notes/${id}`, body),
  delete: (id: number) => api.delete<void>(`/notes/${id}`),
};
