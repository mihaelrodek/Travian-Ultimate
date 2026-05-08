export interface Note {
  id: number;
  accountId: number;
  title: string;
  body: string;
  createdAt: string;
  updatedAt: string;
}

export interface NoteCreate {
  accountId: number;
  title: string;
  body: string;
}

export interface NoteUpdate {
  title: string;
  body: string;
}
