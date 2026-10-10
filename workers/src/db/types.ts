/** 库行类型（与 migrations/0001_init.sql 一一对应） */

export interface UserRow {
  id: number;
  role: 'elder' | 'family';
  name: string;
  phone: string;
  bind_code: string;
  bound_user_id: number | null;
  wx_openid: string | null;
  password_hash: string | null;
  mobile: string | null;
  guard_settings: string | null;
  created_at: string | null;
}

export interface RiskEventRow {
  id: number;
  elder_id: number;
  event_type: string;
  severity: 'LOW' | 'MEDIUM' | 'HIGH';
  details: string | null;
  created_at: string | null;
}

export interface LocationRow {
  id: number;
  elder_id: number;
  latitude: number;
  longitude: number;
  address: string;
  is_sensitive: number;
  created_at: string | null;
}

export interface PaymentRow {
  id: number;
  elder_id: number;
  amount: number;
  payee_name: string;
  payee_account: string | null;
  order_no: string | null;
  created_at: string | null;
}

export interface GeofenceRow {
  id: number;
  elder_id: number;
  name: string;
  latitude: number;
  longitude: number;
  radius: number;
  dwell_minutes: number;
  enabled: number;
  created_at: string | null;
}

export interface RecordingRow {
  id: number;
  elder_id: number;
  session_id: string;
  segment_index: number;
  reason: string;
  place_name: string | null;
  file_name: string;
  original_name: string | null;
  duration_ms: number;
  size_bytes: number;
  sha256: string | null;
  mime_type: string | null;
  recorded_at: string | null;
  latitude: number | null;
  longitude: number | null;
  address: string | null;
  transcript: string | null;
  transcript_status: 'PENDING' | 'DONE' | 'FAILED' | 'SKIPPED';
  transcript_error: string | null;
  fraud_status: 'PENDING' | 'ANALYZING' | 'FRAUD' | 'SUSPECT' | 'SAFE' | 'FAILED';
  fraud_score: number | null;
  fraud_verdict: string | null;
  fraud_labels: string | null;
  suspect_role: string | null;
  keep_as_evidence: number;
  retention_until: string | null;
  cleanup_reason: string | null;
  reviewed_by_family: number;
  created_at: string | null;
}
