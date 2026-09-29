-- Die Nachrichten-ID verhindert doppelte Speicherung bei erneuter Zustellung.
CREATE TABLE message (
    id uuid PRIMARY KEY,
    room_id uuid NOT NULL,
    sender_id varchar NOT NULL,
    sender_name varchar NOT NULL,
    content text NOT NULL,
    sent_at timestamptz NOT NULL
);

-- Bereitet den späteren Lesepfad nach Raum und Zeitpunkt vor.
CREATE INDEX message_room_sent_at_idx ON message (room_id, sent_at DESC);
