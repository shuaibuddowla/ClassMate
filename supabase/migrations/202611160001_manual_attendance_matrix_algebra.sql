BEGIN;
-- 1. Create Session for October 26, 2026 (Monday 10:00 - 10:50 AM)
INSERT INTO classmate.attendance_sessions (
    id, routine_slot_id, started_by, session_secret, started_at, closed_at, status, semester_course_id, batch_id
) VALUES (
    '84f1e428-1db2-4eb5-8e8e-c5db1b369c01',
    '2b6b3152-dbdd-4e26-ad32-e732c52a6adc',
    '27ce19e9-ea97-449b-a0ef-dc18d7b7f4d9',
    'bf30ceb32879ad55df18695fb95a70fc',
    '2026-10-26 10:00:00+06',
    '2026-10-26 10:50:00+06',
    'closed',
    '00d7aa47-9826-4ded-ab91-0f8d2ee35239',
    'de0b3379-54a0-4275-a526-5970295c787e'
) ON CONFLICT (id) DO UPDATE SET
    started_at = EXCLUDED.started_at,
    closed_at = EXCLUDED.closed_at,
    status = EXCLUDED.status;

INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '84f1e428-1db2-4eb5-8e8e-c5db1b369c01',
    'c169e931-28f4-4008-91fd-f3f4fbe4e8f7',
    '2026-10-26 10:05:00+06',
    '2026-10-26 10:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '84f1e428-1db2-4eb5-8e8e-c5db1b369c01',
    '777d7668-1af8-44fd-867d-ae003ccb504f',
    '2026-10-26 10:05:00+06',
    '2026-10-26 10:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '84f1e428-1db2-4eb5-8e8e-c5db1b369c01',
    'e4e20853-8548-44e9-92cf-bb00edb05572',
    '2026-10-26 10:05:00+06',
    '2026-10-26 10:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '84f1e428-1db2-4eb5-8e8e-c5db1b369c01',
    '663a12d0-82b9-458d-b3ee-1155c46bf84f',
    '2026-10-26 10:05:00+06',
    '2026-10-26 10:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '84f1e428-1db2-4eb5-8e8e-c5db1b369c01',
    'dbf5e616-e357-4735-8a95-04ef06610734',
    '2026-10-26 10:05:00+06',
    '2026-10-26 10:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '84f1e428-1db2-4eb5-8e8e-c5db1b369c01',
    'ce661dab-1fe4-4597-bb3c-acaf8a86de6b',
    '2026-10-26 10:05:00+06',
    '2026-10-26 10:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '84f1e428-1db2-4eb5-8e8e-c5db1b369c01',
    '9b5baafb-6d6d-4773-a24f-a2d387433f4c',
    '2026-10-26 10:05:00+06',
    '2026-10-26 10:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '84f1e428-1db2-4eb5-8e8e-c5db1b369c01',
    '941ddcfb-0c78-4fed-8fee-85692274c19f',
    '2026-10-26 10:05:00+06',
    '2026-10-26 10:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '84f1e428-1db2-4eb5-8e8e-c5db1b369c01',
    '912b2f48-1e5f-49d9-b322-2df357d27985',
    '2026-10-26 10:05:00+06',
    '2026-10-26 10:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '84f1e428-1db2-4eb5-8e8e-c5db1b369c01',
    'd374c15b-b3d8-4c8c-8321-72344037f7bc',
    '2026-10-26 10:05:00+06',
    '2026-10-26 10:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '84f1e428-1db2-4eb5-8e8e-c5db1b369c01',
    '01aae2ed-f412-49f7-93b6-da5f1cc6bd65',
    '2026-10-26 10:05:00+06',
    '2026-10-26 10:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '84f1e428-1db2-4eb5-8e8e-c5db1b369c01',
    'b6b11661-89bf-4c6f-abed-685f73871192',
    '2026-10-26 10:05:00+06',
    '2026-10-26 10:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '84f1e428-1db2-4eb5-8e8e-c5db1b369c01',
    '4c9a09e8-da32-40fd-9519-cd0c31498620',
    '2026-10-26 10:05:00+06',
    '2026-10-26 10:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '84f1e428-1db2-4eb5-8e8e-c5db1b369c01',
    '49e73e4f-33eb-4d94-a831-3daf96c47541',
    '2026-10-26 10:05:00+06',
    '2026-10-26 10:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '84f1e428-1db2-4eb5-8e8e-c5db1b369c01',
    '5fca11bc-047f-4f0e-9ab2-3b0b36aa5ca8',
    '2026-10-26 10:05:00+06',
    '2026-10-26 10:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '84f1e428-1db2-4eb5-8e8e-c5db1b369c01',
    '06356e2e-a905-4e8c-bc82-8d2113e921bd',
    '2026-10-26 10:05:00+06',
    '2026-10-26 10:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '84f1e428-1db2-4eb5-8e8e-c5db1b369c01',
    '3b9aee70-9315-4d6a-bf83-e161bf8d6d0f',
    '2026-10-26 10:05:00+06',
    '2026-10-26 10:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '84f1e428-1db2-4eb5-8e8e-c5db1b369c01',
    '27ce19e9-ea97-449b-a0ef-dc18d7b7f4d9',
    '2026-10-26 10:05:00+06',
    '2026-10-26 10:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '84f1e428-1db2-4eb5-8e8e-c5db1b369c01',
    '86a03903-9c38-469d-9d6d-c45686012369',
    '2026-10-26 10:05:00+06',
    '2026-10-26 10:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '84f1e428-1db2-4eb5-8e8e-c5db1b369c01',
    'f1853d76-1380-4a3d-988a-d910f87522a6',
    '2026-10-26 10:05:00+06',
    '2026-10-26 10:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '84f1e428-1db2-4eb5-8e8e-c5db1b369c01',
    'ab536342-01f0-4ba0-b106-aaa216aef17e',
    '2026-10-26 10:05:00+06',
    '2026-10-26 10:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '84f1e428-1db2-4eb5-8e8e-c5db1b369c01',
    '583d1de3-b3ec-4f43-9e61-dbc7e141a3d0',
    '2026-10-26 10:05:00+06',
    '2026-10-26 10:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '84f1e428-1db2-4eb5-8e8e-c5db1b369c01',
    'e748e393-232d-4b94-ae91-0dc400c290b7',
    '2026-10-26 10:05:00+06',
    '2026-10-26 10:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '84f1e428-1db2-4eb5-8e8e-c5db1b369c01',
    'cfa4d2e1-0080-4073-9f40-a836a2761ca1',
    '2026-10-26 10:05:00+06',
    '2026-10-26 10:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
-- 2. Create Session for October 10, 2026 (Saturday 12:00 - 12:50 PM)
INSERT INTO classmate.attendance_sessions (
    id, routine_slot_id, started_by, session_secret, started_at, closed_at, status, semester_course_id, batch_id
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    'ba9b4660-b0a3-4de5-8bd4-b9e279dbf095',
    '27ce19e9-ea97-449b-a0ef-dc18d7b7f4d9',
    '381afce241729ccf2454ea46ec82cbc4',
    '2026-10-10 12:00:00+06',
    '2026-10-10 12:50:00+06',
    'closed',
    '00d7aa47-9826-4ded-ab91-0f8d2ee35239',
    'de0b3379-54a0-4275-a526-5970295c787e'
) ON CONFLICT (id) DO UPDATE SET
    started_at = EXCLUDED.started_at,
    closed_at = EXCLUDED.closed_at,
    status = EXCLUDED.status;

INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    'c169e931-28f4-4008-91fd-f3f4fbe4e8f7',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    '777d7668-1af8-44fd-867d-ae003ccb504f',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    '2d5291fa-ec20-4aeb-a73f-725eaa782fbc',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    '502fd9dc-4392-47af-a777-ae7fc386c4c3',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    '2681adf8-63ec-4d01-b003-1351ec3a2f7d',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    '692456ab-776d-4c02-a8d9-2a10c5a9674f',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    'eafc8180-a760-4cad-999a-e6e9e6d35dce',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    'e4e20853-8548-44e9-92cf-bb00edb05572',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    '663a12d0-82b9-458d-b3ee-1155c46bf84f',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    '7cb2a0e3-c785-4ff0-9c31-6b7c11eb79d1',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    '7121e712-aa69-4bba-9572-233726d704d1',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    '94b65470-f34a-4ad2-a903-86701795617a',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    '50bc49b4-8fbd-4f23-ac27-0a32245b026a',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    'beb3aeaf-6c8a-4f87-a1b3-c18867c7e4bb',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    '67159af4-481b-4b66-a8e0-7b01aa80e22a',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    'dbf5e616-e357-4735-8a95-04ef06610734',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    'ce661dab-1fe4-4597-bb3c-acaf8a86de6b',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    '9b5baafb-6d6d-4773-a24f-a2d387433f4c',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    '941ddcfb-0c78-4fed-8fee-85692274c19f',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    '82521a6d-7461-4bf7-b4d5-c8b89d5d5a5b',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    '2d97f945-6397-4ba9-b2e2-356f154a3636',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    '912b2f48-1e5f-49d9-b322-2df357d27985',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    '097ab438-c1d1-4816-a3b1-8a25a009558b',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    '1c3af8e8-d4ec-4d0a-864d-d735c73d1d04',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    '447551a7-c97d-471e-b6dd-1c24265533ae',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    '1f8a883e-9484-4eeb-92d9-b320cf0750b5',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    'ad6c28a5-18df-4af0-82b0-398ec0e63101',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    'd374c15b-b3d8-4c8c-8321-72344037f7bc',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    '01aae2ed-f412-49f7-93b6-da5f1cc6bd65',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    'b6b11661-89bf-4c6f-abed-685f73871192',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    '4c9a09e8-da32-40fd-9519-cd0c31498620',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    '49e73e4f-33eb-4d94-a831-3daf96c47541',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    '06356e2e-a905-4e8c-bc82-8d2113e921bd',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    '3b9aee70-9315-4d6a-bf83-e161bf8d6d0f',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    '27ce19e9-ea97-449b-a0ef-dc18d7b7f4d9',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    '86a03903-9c38-469d-9d6d-c45686012369',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    'f1853d76-1380-4a3d-988a-d910f87522a6',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    'ab536342-01f0-4ba0-b106-aaa216aef17e',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    'dd57138e-2df8-4cde-8571-aac638b74db2',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    'abf72651-0a72-46dd-ba51-459f85d43726',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    '2d24fff1-67ed-4474-8184-13373c391568',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    '583d1de3-b3ec-4f43-9e61-dbc7e141a3d0',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    '82b03d48-c533-4928-b864-0c58e2adf4d6',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    'fddf1c93-3bb9-4417-a23d-5871fb19521e',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    'e748e393-232d-4b94-ae91-0dc400c290b7',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
INSERT INTO classmate.attendance_records (
    session_id, student_id, marked_at, synced_at, method, confidence
) VALUES (
    '6f94a11c-2234-45aa-9669-e770ba516b02',
    'cfa4d2e1-0080-4073-9f40-a836a2761ca1',
    '2026-10-10 12:05:00+06',
    '2026-10-10 12:50:00+06',
    'rfid',
    'normal'
) ON CONFLICT (session_id, student_id) DO UPDATE SET
    method = EXCLUDED.method,
    confidence = EXCLUDED.confidence;
COMMIT;