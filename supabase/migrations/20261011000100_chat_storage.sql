-- Cloud chat storage: every row is scoped to its authenticated owner.
DO $$ BEGIN
    CREATE TYPE public.message_type AS ENUM ('TEXT', 'VOICE', 'COMMAND', 'ERROR');
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;

CREATE TABLE IF NOT EXISTS public.chat_sessions (
    id TEXT PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    title TEXT NOT NULL DEFAULT 'New Chat',
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS public.chat_messages (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES public.chat_sessions(id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    is_from_user BOOLEAN NOT NULL,
    timestamp BIGINT NOT NULL,
    message_type public.message_type NOT NULL DEFAULT 'TEXT'
);

CREATE INDEX IF NOT EXISTS chat_sessions_user_id_idx ON public.chat_sessions(user_id);
CREATE INDEX IF NOT EXISTS chat_sessions_updated_at_idx ON public.chat_sessions(updated_at DESC);
CREATE INDEX IF NOT EXISTS chat_messages_session_id_idx ON public.chat_messages(session_id);
CREATE INDEX IF NOT EXISTS chat_messages_timestamp_idx ON public.chat_messages(timestamp);

ALTER TABLE public.chat_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.chat_sessions FORCE ROW LEVEL SECURITY;
ALTER TABLE public.chat_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.chat_messages FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can view own sessions" ON public.chat_sessions;
DROP POLICY IF EXISTS "Users can create own sessions" ON public.chat_sessions;
DROP POLICY IF EXISTS "Users can update own sessions" ON public.chat_sessions;
DROP POLICY IF EXISTS "Users can delete own sessions" ON public.chat_sessions;
CREATE POLICY "Users can view own sessions" ON public.chat_sessions FOR SELECT TO authenticated USING (auth.uid() = user_id);
CREATE POLICY "Users can create own sessions" ON public.chat_sessions FOR INSERT TO authenticated WITH CHECK (auth.uid() = user_id);
CREATE POLICY "Users can update own sessions" ON public.chat_sessions FOR UPDATE TO authenticated USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
CREATE POLICY "Users can delete own sessions" ON public.chat_sessions FOR DELETE TO authenticated USING (auth.uid() = user_id);

DROP POLICY IF EXISTS "Users can view messages of own sessions" ON public.chat_messages;
DROP POLICY IF EXISTS "Users can create messages in own sessions" ON public.chat_messages;
DROP POLICY IF EXISTS "Users can delete messages in own sessions" ON public.chat_messages;
CREATE POLICY "Users can view messages of own sessions" ON public.chat_messages FOR SELECT TO authenticated
    USING (EXISTS (SELECT 1 FROM public.chat_sessions s WHERE s.id = session_id AND s.user_id = auth.uid()));
CREATE POLICY "Users can create messages in own sessions" ON public.chat_messages FOR INSERT TO authenticated
    WITH CHECK (EXISTS (SELECT 1 FROM public.chat_sessions s WHERE s.id = session_id AND s.user_id = auth.uid()));
CREATE POLICY "Users can delete messages in own sessions" ON public.chat_messages FOR DELETE TO authenticated
    USING (EXISTS (SELECT 1 FROM public.chat_sessions s WHERE s.id = session_id AND s.user_id = auth.uid()));

REVOKE ALL ON public.chat_sessions, public.chat_messages FROM PUBLIC, anon;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.chat_sessions, public.chat_messages TO authenticated;
GRANT ALL ON public.chat_sessions, public.chat_messages TO service_role;
