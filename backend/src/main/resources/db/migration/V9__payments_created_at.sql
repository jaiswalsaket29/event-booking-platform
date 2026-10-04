-- When each payment attempt was opened: orders attempts and supports admin views.
ALTER TABLE payments ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT now();
