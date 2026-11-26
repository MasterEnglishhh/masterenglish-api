# M05 — JSON Schema snapshot v1

Trạng thái: **ĐÃ CHỐT** (T0, duyệt S1–S12 ngày 26/11/2026). Nguồn quyết định: [brief T0](tasks/T0-brief-contract-freeze.md), [ADR-07..13](01-adrs-07-to-13.md).

Tài liệu này là hợp đồng dữ liệu cho:
- học liệu (`group_materials.body`, `question_materials.body`, envelope revision),
- snapshot nhóm (`attempt_group_snapshots.public_snapshot`, `private_snapshot`),
- snapshot câu (`attempt_question_snapshots.question_snapshot`, `answer_key_snapshot`),
- DTO `ActiveAttemptResponse`, `AttemptSubmitResponse`, `AttemptResultResponse` trả cho frontend (§8).

**Lịch sử thay đổi** (tương thích ngược, vẫn `schema_version = 1`):

| Ngày | Nguồn | Thay đổi |
|---|---|---|
| 26/11/2026 | T0 | Tạo tài liệu |
| 26/11/2026 | Rà soát chéo T0–T7 (C-01, C-02, C-08) | Áp delta đã duyệt: T1 §4.8 (VOCABULARY, `practice_mode` mới, `legacy_backfill`, V-13, cột §2); T2 §4.9 (media qua redirect); T3 §4.10 và T4 §4.4 (tách DTO, `savedAnswers`, `/results`, `partBreakdown`); AD-2 (`refs`/`evidence` ở DTO dùng camelCase). |

## 1. Quy tắc phiên bản

- `schema_version = 1` cho mọi tài liệu ở đây.
- Thêm trường **tùy chọn** hoặc thêm **giá trị enum** là tương thích ngược, giữ `schema_version = 1`. Renderer gặp giá trị enum lạ (ví dụ `document_kind`) thì hiển thị kiểu chung.
- Xóa, đổi tên, hoặc đổi nghĩa một trường thì tăng lên `2`. Snapshot đã ghi giữ nguyên `1` vĩnh viễn, vì snapshot bất biến.
- Mọi object có `additionalProperties: false`. JSON lưu trong DB dùng `snake_case`.

## 2. Mỗi thông tin một nơi lưu (R1, S6, S7)

| Thông tin | Nơi lưu duy nhất |
|---|---|
| Định danh lựa chọn trong lượt làm | `attempt_snapshot_options.id`. UUID sinh riêng cho từng lượt (S7). |
| Thứ tự và nhãn lựa chọn | `attempt_snapshot_options.position` (1–6) và `label` (`chr(64 + position)`) |
| Nội dung lựa chọn được hiện trước khi nộp | `attempt_snapshot_options.body`. `NULL` khi `option_display = LABEL_ONLY` (P1, P2). |
| ID lựa chọn gốc trong revision | `attempt_snapshot_options.source_option_id`. Không bao giờ trả ra API. |
| Đề bài, Part, dạng câu, tham chiếu, học liệu cấp câu | `question_snapshot`. **Không có mảng `options`.** |
| Chủ điểm được gán (Grammar, D9) | Cột `attempt_question_snapshots.topic_id`. JSON chỉ giữ `meta.topic_ids` đầy đủ. |
| Số câu hiển thị | Cột `attempt_question_snapshots.display_number` |
| Đáp án, lời giải, dẫn chứng, transcript lựa chọn P1/P2, transcript câu hỏi P2 | `answer_key_snapshot`. Không có cột `explanation_snapshot` (S6). |
| Học liệu nhóm hiện trước khi nộp | `attempt_group_snapshots.public_snapshot` |
| Học liệu nhóm chỉ hiện sau khi nộp | `attempt_group_snapshots.private_snapshot`. `NULL` khi nhóm không có học liệu sau nộp (T1). |
| Nhóm nguồn của snapshot nhóm | Cột `attempt_group_snapshots.source_group_id` (T1) |
| Media mà lượt làm được phép phát | `attempt_media_refs (attempt_id, media_asset_id)` (T1). Endpoint media chỉ redirect media có trong bảng này. |

Một cột phục vụ truy vấn (ví dụ `part_number`, `schema_version`) được phép lặp lại giá trị có trong JSON **chỉ khi** có CHECK ép hai giá trị bằng nhau.

## 3. JSON Schema (Draft 2020-12)

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "urn:masterenglish:m05:snapshot:v1",
  "title": "M05 snapshot JSON v1",
  "$defs": {
    "Uuid": { "type": "string", "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$" },
    "PlainText": { "type": "string", "minLength": 1, "not": { "pattern": "<\\s*/?\\s*[a-zA-Z!]" } },
    "Key": { "type": "string", "pattern": "^[a-z][a-z0-9_-]{0,31}$" },
    "BlankKey": { "type": "string", "pattern": "^b[1-9]$" },
    "SpeakerLabel": { "type": "string", "pattern": "^(N|[MW]-(Am|Br|Cn|Au))$" },
    "ClockTime": { "type": "string", "pattern": "^((0?[1-9]|1[0-2]):[0-5][0-9] (A\\.M\\.|P\\.M\\.)|([01]?[0-9]|2[0-3]):[0-5][0-9])$" },
    "Visibility": { "enum": ["BEFORE_SUBMIT", "AFTER_SUBMIT"] },
    "Ref": {
      "type": "object", "additionalProperties": false, "required": ["material_key"],
      "properties": { "material_key": { "$ref": "#/$defs/Key" }, "anchor": { "$ref": "#/$defs/Key" } }
    },

    "Inline": {
      "oneOf": [
        { "type": "object", "additionalProperties": false, "required": ["type", "text"],
          "properties": { "type": { "const": "text" },
                          "text": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 4000 }] },
                          "marks": { "type": "array", "uniqueItems": true, "items": { "enum": ["BOLD", "ITALIC", "UNDERLINE"] } } } },
        { "type": "object", "additionalProperties": false, "required": ["type", "blank_key"],
          "properties": { "type": { "const": "blank" }, "blank_key": { "$ref": "#/$defs/BlankKey" } } },
        { "type": "object", "additionalProperties": false, "required": ["type", "position"],
          "properties": { "type": { "const": "insert" }, "position": { "type": "integer", "minimum": 1, "maximum": 4 } } },
        { "type": "object", "additionalProperties": false, "required": ["type"],
          "properties": { "type": { "const": "line_break" } } }
      ]
    },
    "Inlines": { "type": "array", "minItems": 1, "maxItems": 400, "items": { "$ref": "#/$defs/Inline" } },
    "Block": {
      "oneOf": [
        { "type": "object", "additionalProperties": false, "required": ["type", "level", "inlines"],
          "properties": { "type": { "const": "heading" }, "level": { "type": "integer", "minimum": 1, "maximum": 3 },
                          "inlines": { "$ref": "#/$defs/Inlines" } } },
        { "type": "object", "additionalProperties": false, "required": ["type", "inlines"],
          "properties": { "type": { "const": "paragraph" }, "inlines": { "$ref": "#/$defs/Inlines" } } },
        { "type": "object", "additionalProperties": false, "required": ["type", "ordered", "items"],
          "properties": { "type": { "const": "list" }, "ordered": { "type": "boolean" },
                          "items": { "type": "array", "minItems": 1, "maxItems": 50,
                                     "items": { "type": "object", "additionalProperties": false, "required": ["inlines"],
                                                "properties": { "inlines": { "$ref": "#/$defs/Inlines" } } } } } }
      ]
    },

    "AudioBody": {
      "type": "object", "additionalProperties": false,
      "properties": { "duration_seconds": { "type": "integer", "minimum": 1, "maximum": 3600 },
                      "directions_text": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 2000 }] } }
    },
    "ImageBody": {
      "type": "object", "additionalProperties": false, "required": ["alt_text"],
      "properties": { "alt_text": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 500 }] },
                      "caption": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 300 }] } }
    },
    "RichTextBody": {
      "type": "object", "additionalProperties": false, "required": ["document_kind", "blocks"],
      "properties": {
        "document_kind": { "enum": ["EMAIL", "LETTER", "MEMO", "NOTICE", "ANNOUNCEMENT", "ADVERTISEMENT", "ARTICLE",
                                    "REVIEW", "INFORMATION", "FORM", "INVOICE", "WEB_PAGE", "SCHEDULE", "INSTRUCTIONS",
                                    "EXPLANATION", "OTHER"] },
        "title": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 200 }] },
        "header": {
          "type": "object", "additionalProperties": false, "minProperties": 1,
          "properties": {
            "from": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 300 }] },
            "to": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 300 }] },
            "cc": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 300 }] },
            "subject": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 300 }] },
            "date": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 100 }] }
          }
        },
        "blocks": { "type": "array", "minItems": 1, "maxItems": 200, "items": { "$ref": "#/$defs/Block" } }
      }
    },
    "ChatBody": {
      "type": "object", "additionalProperties": false, "required": ["channel", "messages"],
      "properties": {
        "channel": { "enum": ["TEXT_MESSAGE", "ONLINE_CHAT"] },
        "title": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 200 }] },
        "messages": { "type": "array", "minItems": 2, "maxItems": 60,
          "items": { "type": "object", "additionalProperties": false, "required": ["anchor", "author", "time", "text"],
            "properties": { "anchor": { "$ref": "#/$defs/Key" },
                            "author": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 100 }] },
                            "time": { "$ref": "#/$defs/ClockTime" },
                            "text": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 2000 }] } } } }
      }
    },
    "TableBody": {
      "type": "object", "additionalProperties": false, "required": ["headers", "rows"],
      "properties": {
        "title": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 200 }] },
        "headers": { "type": "array", "minItems": 1, "maxItems": 10,
                     "items": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 100 }] } },
        "rows": { "type": "array", "minItems": 1, "maxItems": 50,
                  "items": { "type": "array", "minItems": 1, "maxItems": 10,
                             "items": { "type": "string", "maxLength": 500, "not": { "pattern": "<\\s*/?\\s*[a-zA-Z!]" } } } },
        "caption": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 300 }] }
      }
    },
    "TranscriptBody": {
      "type": "object", "additionalProperties": false, "required": ["segments"],
      "properties": {
        "segments": { "type": "array", "minItems": 1, "maxItems": 400,
          "items": { "oneOf": [
            { "type": "object", "additionalProperties": false, "required": ["type", "speaker", "text"],
              "properties": { "type": { "const": "speech" }, "speaker": { "$ref": "#/$defs/SpeakerLabel" },
                              "text": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 4000 }] },
                              "start_ms": { "type": "integer", "minimum": 0 },
                              "end_ms": { "type": "integer", "minimum": 1 } } },
            { "type": "object", "additionalProperties": false, "required": ["type", "seconds"],
              "properties": { "type": { "const": "pause" }, "seconds": { "type": "integer", "minimum": 1, "maximum": 60 } } }
          ] } }
      }
    },

    "Material": {
      "type": "object", "additionalProperties": false,
      "required": ["material_key", "material_type", "visibility", "position", "body"],
      "properties": {
        "material_key": { "$ref": "#/$defs/Key" },
        "material_type": { "enum": ["AUDIO", "IMAGE", "RICH_TEXT", "CHAT", "TABLE", "TRANSCRIPT"] },
        "visibility": { "$ref": "#/$defs/Visibility" },
        "position": { "type": "integer", "minimum": 1, "maximum": 20 },
        "media_asset_id": { "$ref": "#/$defs/Uuid" },
        "body": { "type": "object" }
      },
      "allOf": [
        { "if": { "properties": { "material_type": { "const": "AUDIO" } } },
          "then": { "required": ["media_asset_id"], "properties": { "visibility": { "const": "BEFORE_SUBMIT" }, "body": { "$ref": "#/$defs/AudioBody" } } } },
        { "if": { "properties": { "material_type": { "const": "IMAGE" } } },
          "then": { "required": ["media_asset_id"], "properties": { "visibility": { "const": "BEFORE_SUBMIT" }, "body": { "$ref": "#/$defs/ImageBody" } } } },
        { "if": { "properties": { "material_type": { "const": "RICH_TEXT" } } },
          "then": { "not": { "required": ["media_asset_id"] }, "properties": { "body": { "$ref": "#/$defs/RichTextBody" } } } },
        { "if": { "properties": { "material_type": { "const": "CHAT" } } },
          "then": { "not": { "required": ["media_asset_id"] }, "properties": { "body": { "$ref": "#/$defs/ChatBody" } } } },
        { "if": { "properties": { "material_type": { "const": "TABLE" } } },
          "then": { "not": { "required": ["media_asset_id"] }, "properties": { "body": { "$ref": "#/$defs/TableBody" } } } },
        { "if": { "properties": { "material_type": { "const": "TRANSCRIPT" } } },
          "then": { "not": { "required": ["media_asset_id"] }, "properties": { "visibility": { "const": "AFTER_SUBMIT" }, "body": { "$ref": "#/$defs/TranscriptBody" } } } }
      ]
    },

    "GroupPublicSnapshot": {
      "type": "object", "additionalProperties": false,
      "required": ["schema_version", "category", "part_number", "group_type", "materials"],
      "properties": {
        "schema_version": { "const": 1 },
        "category": { "const": "TOEIC" },
        "part_number": { "enum": [3, 4, 6, 7] },
        "group_type": { "enum": ["CONVERSATION", "TALK", "TEXT_COMPLETION", "PASSAGE"] },
        "title": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 200 }] },
        "speaker_count": { "type": "integer", "minimum": 1, "maximum": 3 },
        "materials": { "type": "array", "minItems": 1, "maxItems": 10,
                       "items": { "allOf": [{ "$ref": "#/$defs/Material" },
                                            { "properties": { "visibility": { "const": "BEFORE_SUBMIT" } } }] } }
      }
    },
    "GroupPrivateSnapshot": {
      "type": "object", "additionalProperties": false, "required": ["schema_version", "materials"],
      "properties": {
        "schema_version": { "const": 1 },
        "materials": { "type": "array", "maxItems": 10,
                       "items": { "allOf": [{ "$ref": "#/$defs/Material" },
                                            { "properties": { "visibility": { "const": "AFTER_SUBMIT" } } }] } }
      }
    },

    "QuestionSnapshot": {
      "type": "object", "additionalProperties": false,
      "required": ["schema_version", "category", "part_number", "question_type_code", "practice_mode",
                   "option_order", "option_display", "question_kind", "prompt", "refs", "materials", "meta"],
      "properties": {
        "schema_version": { "const": 1 },
        "category": { "enum": ["VOCABULARY", "GRAMMAR", "TOEIC"] },
        "part_number": { "type": ["integer", "null"], "minimum": 1, "maximum": 7 },
        "question_type_code": { "const": "SINGLE_CHOICE" },
        "practice_mode": { "enum": ["STANDARD", "TRANSLATE_EN_VI", "TRANSLATE_VI_EN", "LISTEN_AND_CHOOSE", "FILL_OPTION"] },
        "legacy_backfill": { "const": true },
        "option_order": { "enum": ["FIXED", "SHUFFLED"] },
        "option_display": { "enum": ["TEXT", "LABEL_ONLY"] },
        "question_kind": { "enum": ["STANDARD", "GRAPHIC", "INTENT", "SENTENCE_INSERTION", "INSERTION_POSITION", "NOT_EXCEPT"] },
        "blank_key": { "$ref": "#/$defs/BlankKey" },
        "prompt": { "oneOf": [{ "type": "null" }, { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 10000 }] }] },
        "insert_sentence": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 1000 }] },
        "refs": { "type": "array", "maxItems": 10, "items": { "$ref": "#/$defs/Ref" } },
        "materials": { "type": "array", "maxItems": 4,
                       "items": { "allOf": [{ "$ref": "#/$defs/Material" },
                                            { "properties": { "visibility": { "const": "BEFORE_SUBMIT" } } }] } },
        "meta": {
          "type": "object", "additionalProperties": false, "required": ["topic_ids", "word_sense_ids"],
          "properties": { "topic_ids": { "type": "array", "uniqueItems": true, "items": { "$ref": "#/$defs/Uuid" } },
                          "word_sense_ids": { "type": "array", "uniqueItems": true, "items": { "$ref": "#/$defs/Uuid" } } }
        }
      },
      "allOf": [
        { "if": { "properties": { "category": { "const": "GRAMMAR" } } },
          "then": { "properties": { "part_number": { "const": null }, "option_display": { "const": "TEXT" },
                                    "question_kind": { "const": "STANDARD" }, "prompt": { "type": "string" },
                                    "materials": { "maxItems": 0 } } } },
        { "if": { "properties": { "category": { "const": "GRAMMAR" } } },
          "then": { "properties": { "practice_mode": { "enum": ["STANDARD", "FILL_OPTION"] } } } },
        { "if": { "properties": { "category": { "const": "VOCABULARY" } } },
          "then": { "properties": { "part_number": { "const": null }, "option_display": { "const": "TEXT" },
                                    "question_kind": { "const": "STANDARD" }, "materials": { "maxItems": 2 } } } },
        { "if": { "properties": { "category": { "const": "TOEIC" } } },
          "then": { "properties": { "part_number": { "type": "integer" }, "practice_mode": { "const": "STANDARD" },
                                    "option_order": { "const": "FIXED" } } } },
        { "if": { "properties": { "part_number": { "enum": [1, 2] } }, "required": ["part_number"] },
          "then": { "properties": { "option_display": { "const": "LABEL_ONLY" }, "prompt": { "type": "null" },
                                    "question_kind": { "const": "STANDARD" } } },
          "else": { "properties": { "option_display": { "const": "TEXT" } } } },
        { "if": { "properties": { "part_number": { "const": 6 } }, "required": ["part_number"] },
          "then": { "required": ["blank_key"], "properties": { "question_kind": { "enum": ["STANDARD", "SENTENCE_INSERTION"] } } },
          "else": { "not": { "required": ["blank_key"] } } },
        { "if": { "properties": { "question_kind": { "const": "INSERTION_POSITION" } } },
          "then": { "required": ["insert_sentence"], "properties": { "part_number": { "const": 7 }, "refs": { "minItems": 1 } } },
          "else": { "not": { "required": ["insert_sentence"] } } },
        { "if": { "properties": { "question_kind": { "const": "GRAPHIC" } } },
          "then": { "properties": { "part_number": { "enum": [3, 4] }, "refs": { "minItems": 1 } } } },
        { "if": { "properties": { "question_kind": { "const": "SENTENCE_INSERTION" } } },
          "then": { "properties": { "part_number": { "const": 6 } } } }
      ]
    },

    "AnswerKeySnapshot": {
      "type": "object", "additionalProperties": false,
      "required": ["schema_version", "correct_option_snapshot_id", "correct_option_ordinal", "explanation", "evidence"],
      "properties": {
        "schema_version": { "const": 1 },
        "correct_option_snapshot_id": { "$ref": "#/$defs/Uuid" },
        "correct_option_ordinal": { "type": "integer", "minimum": 1, "maximum": 6 },
        "explanation": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 20000 }] },
        "evidence": { "type": "array", "maxItems": 10, "items": { "$ref": "#/$defs/Ref" } },
        "option_transcripts": { "type": "array", "minItems": 3, "maxItems": 4,
          "items": { "type": "object", "additionalProperties": false, "required": ["ordinal", "label", "speaker", "text"],
            "properties": { "ordinal": { "type": "integer", "minimum": 1, "maximum": 4 },
                            "label": { "enum": ["A", "B", "C", "D"] },
                            "speaker": { "$ref": "#/$defs/SpeakerLabel" },
                            "text": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 1000 }] } } } },
        "prompt_transcript": {
          "type": "object", "additionalProperties": false, "required": ["speaker", "text"],
          "properties": { "speaker": { "$ref": "#/$defs/SpeakerLabel" },
                          "text": { "allOf": [{ "$ref": "#/$defs/PlainText" }, { "maxLength": 1000 }] } } }
      }
    }
  }
}
```

## 4. Ràng buộc ngoài JSON Schema (validator Java phải kiểm)

| Mã | Ràng buộc |
|---|---|
| V-01 | `TableBody`: mọi `rows[i]` có đúng số phần tử bằng `headers`. |
| V-02 | `ChatBody`: `messages[].anchor` duy nhất trong tài liệu. |
| V-03 | Trong một nhóm, mỗi `blank_key` của câu con xuất hiện đúng một lần dưới dạng inline `blank` trong `materials`, và ngược lại. P6 có đúng `b1..b4`. |
| V-04 | Tài liệu có inline `insert` thì có đủ `position` 1–4, mỗi số đúng một lần. |
| V-05 | `refs[].material_key` trỏ tới material có trong nhóm hoặc câu. `anchor` (nếu có) trỏ tới `messages[].anchor` của một `CHAT`. Câu `INSERTION_POSITION` có ref tới tài liệu chứa inline `insert`. |
| V-06 | `option_transcripts` có khi và chỉ khi `part_number ∈ {1,2}`. Độ dài là 4 với P1, 3 với P2. `label = chr(64 + ordinal)`. |
| V-07 | `prompt_transcript` có khi và chỉ khi `part_number = 2`. |
| V-08 | `correct_option_snapshot_id` và `correct_option_ordinal` khớp đúng một dòng `attempt_snapshot_options` của câu. |
| V-09 | `speaker_count` có khi `part_number = 3`, giá trị ∈ {2, 3}, bằng số nhãn `M-*`/`W-*` khác nhau trong `TRANSCRIPT` của nhóm. |
| V-10 | `TRANSCRIPT.segments`: `end_ms > start_ms` khi có cả hai. |
| V-11 | Header email không chứa `<` theo sau là chữ cái. Viết `Tên (a@b.example)`, không viết `Tên <a@b.example>`. |
| V-12 | `practice_mode`: GRAMMAR giữ giá trị của envelope (`STANDARD`/`FILL_OPTION`). TOEIC luôn là `STANDARD`. |
| V-13 | `prompt = null` chỉ được phép với TOEIC P1/P2/P6, hoặc VOCABULARY `LISTEN_AND_CHOOSE` (T1 §4.8). |

Mã V-14..V-17 thuộc kiểm định nguồn theo Part của `practice` (brief T2, `SourceIntegrityValidator`).

Trường `legacy_backfill` chỉ có ở dòng V13 mà V15 chuyển sang v1. Snapshot mới không bao giờ ghi trường này, và mapper bỏ qua nó (T1 §4.8).

## 5. Ví dụ hợp lệ

Nội dung ví dụ do nhóm tự viết, không chép đề ETS.

### 5.1. Học liệu — đủ 6 loại

```json
{ "material_key": "audio", "material_type": "AUDIO", "visibility": "BEFORE_SUBMIT", "position": 1,
  "media_asset_id": "3c9e1f20-7a41-4b6e-9d2c-5f8a0b1c2d31",
  "body": { "duration_seconds": 41, "directions_text": "Listen to the conversation and answer three questions." } }
```

```json
{ "material_key": "photo", "material_type": "IMAGE", "visibility": "BEFORE_SUBMIT", "position": 1,
  "media_asset_id": "4d0f2a31-8b52-4c7f-8e3d-6a9b1c2d3e41",
  "body": { "alt_text": "Two workers are carrying a long table through an open doorway." } }
```

```json
{ "material_key": "notice", "material_type": "RICH_TEXT", "visibility": "BEFORE_SUBMIT", "position": 1,
  "body": { "document_kind": "NOTICE", "title": "Parking garage maintenance",
    "blocks": [
      { "type": "heading", "level": 2, "inlines": [ { "type": "text", "text": "Level B closed on Saturday" } ] },
      { "type": "paragraph", "inlines": [
        { "type": "text", "text": "Please use the visitor lot on Elm Street. " },
        { "type": "text", "text": "Vehicles left on Level B will be moved.", "marks": ["BOLD"] } ] },
      { "type": "list", "ordered": false, "items": [
        { "inlines": [ { "type": "text", "text": "Saturday, 7:00 A.M. to 6:00 P.M." } ] },
        { "inlines": [ { "type": "text", "text": "Questions: facilities desk, extension 214" } ] } ] } ] } }
```

```json
{ "material_key": "chat", "material_type": "CHAT", "visibility": "BEFORE_SUBMIT", "position": 2,
  "body": { "channel": "TEXT_MESSAGE",
    "messages": [
      { "anchor": "t1043", "author": "Mara Lindqvist", "time": "10:43 A.M.", "text": "The caterer just called. They can only deliver at noon." },
      { "anchor": "t1047", "author": "Kenji Watanabe", "time": "10:47 A.M.", "text": "That works. I'll ask reception to hold the boardroom." } ] } }
```

```json
{ "material_key": "graphic", "material_type": "TABLE", "visibility": "BEFORE_SUBMIT", "position": 2,
  "body": { "title": "Training rooms - Thursday", "headers": ["Room", "Available from"],
    "rows": [ ["Room A", "9:00 A.M."], ["Room B", "Closed for repair"], ["Room C", "11:00 A.M."], ["Room D", "2:00 P.M."] ] } }
```

```json
{ "material_key": "transcript", "material_type": "TRANSCRIPT", "visibility": "AFTER_SUBMIT", "position": 1,
  "body": { "segments": [
    { "type": "speech", "speaker": "N", "text": "Questions 41 through 43 refer to the following conversation with three speakers and schedule." },
    { "type": "speech", "speaker": "W-Am", "text": "Daniel, Priya, the projector in Room B stopped working again this morning." },
    { "type": "speech", "speaker": "M-Cn", "text": "That's the third time this month. Should we move Thursday's workshop?" },
    { "type": "speech", "speaker": "W-Br", "text": "Room D is free on Thursday, but only in the afternoon." },
    { "type": "speech", "speaker": "M-Cn", "text": "Then let's start at two thirty. I'll update the invitation." },
    { "type": "speech", "speaker": "N", "text": "41. What problem does the woman mention?" },
    { "type": "pause", "seconds": 8 },
    { "type": "speech", "speaker": "N", "text": "42. Look at the graphic. Which room will the workshop most likely use?" },
    { "type": "pause", "seconds": 8 } ] } }
```

Ánh xạ sang bảng projection `group_materials` / `question_materials`:

| JSON | Cột |
|---|---|
| `material_key` | `document_key` |
| `material_type` | `material_type` |
| `position` | `position` |
| `media_asset_id` | `media_id` |
| `visibility` | Không lưu. Cột GENERATED từ `usage_role`: `STIMULUS` → `BEFORE_SUBMIT`, `TRANSCRIPT`/`EXPLANATION` → `AFTER_SUBMIT`. |
| `body` | `body` (giữ nguyên object) |

### 5.2. Part 3: ba người nói, có graphic và transcript

`public_snapshot`:
```json
{ "schema_version": 1, "category": "TOEIC", "part_number": 3, "group_type": "CONVERSATION", "speaker_count": 3,
  "materials": [
    { "material_key": "audio", "material_type": "AUDIO", "visibility": "BEFORE_SUBMIT", "position": 1,
      "media_asset_id": "3c9e1f20-7a41-4b6e-9d2c-5f8a0b1c2d31", "body": { "duration_seconds": 41 } },
    { "material_key": "graphic", "material_type": "TABLE", "visibility": "BEFORE_SUBMIT", "position": 2,
      "body": { "title": "Training rooms - Thursday", "headers": ["Room", "Available from"],
                "rows": [ ["Room A", "9:00 A.M."], ["Room B", "Closed for repair"], ["Room C", "11:00 A.M."], ["Room D", "2:00 P.M."] ] } } ] }
```

`private_snapshot`:
```json
{ "schema_version": 1,
  "materials": [
    { "material_key": "transcript", "material_type": "TRANSCRIPT", "visibility": "AFTER_SUBMIT", "position": 1,
      "body": { "segments": [
        { "type": "speech", "speaker": "W-Am", "text": "Daniel, Priya, the projector in Room B stopped working again this morning." },
        { "type": "speech", "speaker": "M-Cn", "text": "That's the third time this month. Should we move Thursday's workshop?" },
        { "type": "speech", "speaker": "W-Br", "text": "Room D is free on Thursday, but only in the afternoon." },
        { "type": "speech", "speaker": "M-Cn", "text": "Then let's start at two thirty. I'll update the invitation." },
        { "type": "pause", "seconds": 8 } ] } } ] }
```

`question_snapshot` của câu 42:
```json
{ "schema_version": 1, "category": "TOEIC", "part_number": 3, "question_type_code": "SINGLE_CHOICE",
  "practice_mode": "STANDARD", "option_order": "FIXED", "option_display": "TEXT", "question_kind": "GRAPHIC",
  "prompt": "Look at the graphic. Which room will the workshop most likely use?",
  "refs": [ { "material_key": "graphic" } ], "materials": [],
  "meta": { "topic_ids": [], "word_sense_ids": [] } }
```

`answer_key_snapshot` của câu 42:
```json
{ "schema_version": 1, "correct_option_snapshot_id": "5b6c7d8e-2222-4c3d-9e4f-000000000424",
  "correct_option_ordinal": 4,
  "explanation": "The woman says Room D is free only in the afternoon, and the man moves the start to two thirty. The schedule shows Room D opens at 2:00 P.M.",
  "evidence": [ { "material_key": "graphic" }, { "material_key": "transcript" } ] }
```

### 5.3. Part 6: bốn `blank_key`, một câu `SENTENCE_INSERTION`

`public_snapshot`:
```json
{ "schema_version": 1, "category": "TOEIC", "part_number": 6, "group_type": "TEXT_COMPLETION",
  "materials": [
    { "material_key": "email", "material_type": "RICH_TEXT", "visibility": "BEFORE_SUBMIT", "position": 1,
      "body": { "document_kind": "EMAIL",
        "header": { "from": "Lena Ortiz (l.ortiz@harbourprint.example)", "to": "Sam Achebe (s.achebe@northfield.example)",
                    "subject": "Your program order", "date": "14 March" },
        "blocks": [
          { "type": "paragraph", "inlines": [
            { "type": "text", "text": "Thank you for choosing Harbour Print. Your order of 300 conference programs has been " },
            { "type": "blank", "blank_key": "b1" },
            { "type": "text", "text": " and will ship on Monday." } ] },
          { "type": "paragraph", "inlines": [
            { "type": "text", "text": "Because the cover uses a " },
            { "type": "blank", "blank_key": "b2" },
            { "type": "text", "text": " paper, please allow one extra day for drying. " },
            { "type": "blank", "blank_key": "b3" } ] },
          { "type": "paragraph", "inlines": [
            { "type": "text", "text": "If you need to change the delivery address, reply to this e-mail " },
            { "type": "blank", "blank_key": "b4" },
            { "type": "text", "text": " Friday." } ] } ] } } ] }
```

Câu `b3` (`display_number = 133`):
```json
{ "schema_version": 1, "category": "TOEIC", "part_number": 6, "question_type_code": "SINGLE_CHOICE",
  "practice_mode": "STANDARD", "option_order": "FIXED", "option_display": "TEXT",
  "question_kind": "SENTENCE_INSERTION", "blank_key": "b3", "prompt": null,
  "refs": [ { "material_key": "email" } ], "materials": [],
  "meta": { "topic_ids": [], "word_sense_ids": [] } }
```

Câu `b1` (`display_number = 131`):
```json
{ "schema_version": 1, "category": "TOEIC", "part_number": 6, "question_type_code": "SINGLE_CHOICE",
  "practice_mode": "STANDARD", "option_order": "FIXED", "option_display": "TEXT",
  "question_kind": "STANDARD", "blank_key": "b1", "prompt": null,
  "refs": [ { "material_key": "email" } ], "materials": [],
  "meta": { "topic_ids": [], "word_sense_ids": [] } }
```

Các lựa chọn của câu `SENTENCE_INSERTION` là câu hoàn chỉnh trong `attempt_snapshot_options.body`, ví dụ `We apologize for the delay this may cause.`

### 5.4. Part 7 đôi: `RICH_TEXT` có `[1]–[4]` và `CHAT`

`public_snapshot`:
```json
{ "schema_version": 1, "category": "TOEIC", "part_number": 7, "group_type": "PASSAGE",
  "materials": [
    { "material_key": "memo", "material_type": "RICH_TEXT", "visibility": "BEFORE_SUBMIT", "position": 1,
      "body": { "document_kind": "MEMO",
        "header": { "from": "Office Services", "to": "All staff", "subject": "Quarterly lunch", "date": "2 May" },
        "blocks": [
          { "type": "paragraph", "inlines": [
            { "type": "text", "text": "Our quarterly staff lunch will be held in the boardroom on Friday. " },
            { "type": "insert", "position": 1 },
            { "type": "text", "text": " Vegetarian options will be available. " },
            { "type": "insert", "position": 2 } ] },
          { "type": "paragraph", "inlines": [
            { "type": "text", "text": "Please confirm your attendance by Wednesday. " },
            { "type": "insert", "position": 3 },
            { "type": "text", "text": " Late replies cannot be included in the order. " },
            { "type": "insert", "position": 4 } ] } ] } },
    { "material_key": "chat", "material_type": "CHAT", "visibility": "BEFORE_SUBMIT", "position": 2,
      "body": { "channel": "TEXT_MESSAGE",
        "messages": [
          { "anchor": "t1043", "author": "Mara Lindqvist", "time": "10:43 A.M.", "text": "The caterer just called. They can only deliver at noon." },
          { "anchor": "t1047", "author": "Kenji Watanabe", "time": "10:47 A.M.", "text": "That works. I'll ask reception to hold the boardroom." },
          { "anchor": "t1049", "author": "Mara Lindqvist", "time": "10:49 A.M.", "text": "Perfect. I'll update the memo." } ] } } ] }
```

Câu `INSERTION_POSITION` (`display_number = 176`). Các lựa chọn có `body` là `[1]`, `[2]`, `[3]`, `[4]`.
```json
{ "schema_version": 1, "category": "TOEIC", "part_number": 7, "question_type_code": "SINGLE_CHOICE",
  "practice_mode": "STANDARD", "option_order": "FIXED", "option_display": "TEXT",
  "question_kind": "INSERTION_POSITION",
  "prompt": "In which of the positions marked [1], [2], [3], and [4] does the following sentence best belong?",
  "insert_sentence": "Lunch will be served at noon.",
  "refs": [ { "material_key": "memo" } ], "materials": [],
  "meta": { "topic_ids": [], "word_sense_ids": [] } }
```

Câu `INTENT` (`display_number = 177`):
```json
{ "schema_version": 1, "category": "TOEIC", "part_number": 7, "question_type_code": "SINGLE_CHOICE",
  "practice_mode": "STANDARD", "option_order": "FIXED", "option_display": "TEXT", "question_kind": "INTENT",
  "prompt": "At 10:47 A.M., what does Mr. Watanabe most likely mean when he writes, \"That works\"?",
  "refs": [ { "material_key": "chat", "anchor": "t1047" } ], "materials": [],
  "meta": { "topic_ids": [], "word_sense_ids": [] } }
```

`answer_key_snapshot` của câu 176:
```json
{ "schema_version": 1, "correct_option_snapshot_id": "6c7d8e9f-3333-4d4e-8f50-000000001761",
  "correct_option_ordinal": 1,
  "explanation": "The sentence about noon follows the first mention of the lunch and matches the delivery time in the chat.",
  "evidence": [ { "material_key": "memo" }, { "material_key": "chat", "anchor": "t1043" } ] }
```

### 5.5. Part 1 và Part 2

Câu P1 (không thuộc nhóm). Bốn lựa chọn có `label` A–D, `body = NULL`.
```json
{ "schema_version": 1, "category": "TOEIC", "part_number": 1, "question_type_code": "SINGLE_CHOICE",
  "practice_mode": "STANDARD", "option_order": "FIXED", "option_display": "LABEL_ONLY", "question_kind": "STANDARD",
  "prompt": null, "refs": [],
  "materials": [
    { "material_key": "photo", "material_type": "IMAGE", "visibility": "BEFORE_SUBMIT", "position": 1,
      "media_asset_id": "4d0f2a31-8b52-4c7f-8e3d-6a9b1c2d3e41",
      "body": { "alt_text": "Two workers are carrying a long table through an open doorway." } },
    { "material_key": "audio", "material_type": "AUDIO", "visibility": "BEFORE_SUBMIT", "position": 2,
      "media_asset_id": "7e8f9a0b-4444-4e5f-9a61-000000000001", "body": { "duration_seconds": 19 } } ],
  "meta": { "topic_ids": [], "word_sense_ids": [] } }
```

`answer_key_snapshot` P1:
```json
{ "schema_version": 1, "correct_option_snapshot_id": "8f9a0b1c-5555-4f60-8b72-000000000013",
  "correct_option_ordinal": 3, "explanation": "Only statement C describes two people moving a table.",
  "evidence": [],
  "option_transcripts": [
    { "ordinal": 1, "label": "A", "speaker": "M-Au", "text": "They're painting a doorway." },
    { "ordinal": 2, "label": "B", "speaker": "M-Au", "text": "They're folding some chairs." },
    { "ordinal": 3, "label": "C", "speaker": "M-Au", "text": "They're carrying a table." },
    { "ordinal": 4, "label": "D", "speaker": "M-Au", "text": "They're closing a window." } ] }
```

`answer_key_snapshot` P2:
```json
{ "schema_version": 1, "correct_option_snapshot_id": "8f9a0b1c-5555-4f60-8b72-000000000021",
  "correct_option_ordinal": 1, "explanation": "Response A says where the invoices go.",
  "evidence": [],
  "prompt_transcript": { "speaker": "W-Br", "text": "Where should I leave these invoices?" },
  "option_transcripts": [
    { "ordinal": 1, "label": "A", "speaker": "M-Cn", "text": "In the tray on my desk." },
    { "ordinal": 2, "label": "B", "speaker": "M-Cn", "text": "Yes, I paid them yesterday." },
    { "ordinal": 3, "label": "C", "speaker": "M-Cn", "text": "About twenty pages." } ] }
```

## 6. Ánh xạ seal (projection → snapshot)

Thứ tự trong transaction start với nguồn `PRACTICE_SET`:

1. Nhận `SealSource` từ `PracticeEvents.loadSetForSealing` ([CONTRACT §2.1](../practice/CONTRACT.md)).
2. **Mỗi nhóm → một dòng `attempt_group_snapshots`.** `ordinal` tăng theo `set_items.position`. Học liệu `BEFORE_SUBMIT` vào `public_snapshot.materials`. Học liệu `AFTER_SUBMIT` vào `private_snapshot.materials`.
3. **Mỗi câu → một dòng `attempt_question_snapshots`.**
   - `position = display_number` = thứ tự câu trong lượt (theo nhóm, rồi `group_position`).
   - P1, P2 có `option_display = LABEL_ONLY`.
4. **Mỗi lựa chọn → một dòng `attempt_snapshot_options`.**
   - `id = UUID.randomUUID()`, sinh ở Java để có trước khi ghi `answer_key_snapshot`.
   - `label = chr(64 + position)`.
   - `source_option_id` = ID trong envelope.
   - `body = NULL` nếu `LABEL_ONLY`.
   - `is_correct = NULL`.
5. **`answer_key_snapshot`.**
   - `correct_option_snapshot_id` = `id` mới của lựa chọn có `source_option_id = correctSourceOptionId`. Kiểm V-08.
   - Với `LABEL_ONLY`, `option_transcripts` lấy từ `body` và `speaker` của option trong envelope.
6. **Slot đáp án**: `INSERT … SELECT` từ snapshot của chính attempt, `version = 0`, `selected_option_id = NULL`.
7. **`attempt_media_refs`**: mọi `media_asset_id` xuất hiện trong snapshot.
8. **`attempts`**:
   - `scoring_version = 2`, `snapshot_sealed_at = now`, `last_active_at = now`, `total_count = N`.
   - `source_config` chỉ chứa các khóa trong allowlist: `category`, `topicIds`, `questionCount`, `practiceSetId`, `mode`.

Nguồn `GRAMMAR_TOPIC` hoặc `MIXED_GRAMMAR`: không có nhóm, `category = GRAMMAR`, `topic_id` là chủ điểm được gán theo D9, `option_display = TEXT`.

## 7. Quy tắc render (R6)

| Node | React |
|---|---|
| `heading` | `<h3>`/`<h4>`/`<h5>` theo `level` 1/2/3 (đề bài đã có tiêu đề cấp trên) |
| `paragraph` | `<p>` |
| `list` | `<ol>` hoặc `<ul>` |
| `text` | chuỗi; `marks` → `<strong>`, `<em>`, `<u>` |
| `blank` | `BlankSlot` hiển thị `display_number` của câu có cùng `blank_key` trong nhóm |
| `insert` | `InsertMarker` hiển thị `— [n] —` |
| `line_break` | `<br/>` |

- **Cấm `dangerouslySetInnerHTML`.** Text là plain text. Validator đã chặn HTML bằng regex `<\s*/?\s*[a-zA-Z!]`.
- Chuỗi `{{blank:b1}}` / `{{insert:1}}` chỉ được phép làm cú pháp nhập liệu của công cụ import. Validator đổi chúng thành node trước khi lưu. DB và API không bao giờ chứa chuỗi template.

## 8. DTO trả cho frontend

Nguồn chi tiết và JSON mẫu đầy đủ: brief T3 §5.1 (start, GET, media) và brief T4 §5.1 (PUT, submit, `/results`). Khi T3 và T4 khác nhau, T4 thắng.

**Quy tắc chung:**
- Tên trường cấp DTO dùng `camelCase`. `materials[].body` đi **nguyên văn** (`snake_case`), vì đó là tài liệu có phiên bản riêng.
- Phần tử `refs[]` và `review.evidence[]` là DTO, không phải body, nên dùng camelCase `{ "materialKey": "…", "anchor": "…" | null }`. Backend map từ `material_key`/`anchor` của JSON lưu trong DB (AD-2).
- Trạng thái lượt làm: `IN_PROGRESS`, `SUBMITTED`, `ABANDONED` (S2: không có `EXPIRED`).
- Không có trường `version` của attempt trong DTO.

**Ba loại response:**

| DTO | Endpoint | Ghi chú |
|---|---|---|
| `ActiveAttemptResponse` | `POST /api/v1/attempts` (201/200), `GET /api/v1/attempts/{id}` ở **mọi** trạng thái | Không bao giờ đọc `private_snapshot` hay `answer_key_snapshot` (T4 O2). Lượt `SUBMITTED` cũng trả hình dạng này, chỉ đọc. |
| `AttemptSubmitResponse` | `POST /api/v1/attempts/{id}/submit` | `{attemptId, status, result, resultsUrl}` (T4 O3) |
| `AttemptResultResponse` | `GET /api/v1/attempts/{id}/results`, chỉ khi `SUBMITTED` | Chưa nộp hoặc đã bỏ → 409 `ATTEMPT_NOT_SUBMITTED` (`attemptStatus`) |

**Trường:**

| Trường | `ActiveAttemptResponse` | `AttemptResultResponse` |
|---|---|---|
| `id`, `category`, `attemptMode`, `status`, `source{type, practiceSetId, topicIds}` | ✓ | ✓ |
| `totalCount`, `startedAt`, `lastActiveAt`, `deadlineAt` | ✓ | — |
| `groups[]`: `groupSnapshotId`, `ordinal`, `partNumber`, `groupType`, `title`, `speakerCount`, `materials[]` (chỉ `public_snapshot`) | ✓ | ✓ |
| `groups[].reviewMaterials` (từ `private_snapshot`) | ✗ | ✓ |
| `questions[]`: `snapshotId`, `ordinal`, `displayNumber`, `groupSnapshotId`, `partNumber`, `questionKind`, `optionDisplay`, `prompt`, `blankKey`, `insertSentence`, `refs`, `materials`, `options[{optionId, label, body}]` | ✓ | ✓ |
| `savedAnswers`: map `snapshotId → {selectedOptionId, version}`, có cho mọi câu | ✓ | ✗ |
| `questions[].answer`: `{selectedOptionId, isCorrect}` | ✗ | ✓ |
| `questions[].review`: `correctOptionId`, `correctOptionLabel`, `explanation`, `evidence`, `optionTranscripts[{label, speaker, text}]`, `promptTranscript{speaker, text}` | ✗ | ✓ |
| `result`: `totalCount`, `correctCount`, `wrongCount`, `unansweredCount`, `accuracyPercent`, `listeningCorrect`, `readingCorrect`, `partBreakdown[{partNumber, totalCount, correctCount, wrongCount, unansweredCount}]`, `submittedAt`, `effectiveFinishedAt`, `submitReason`, `durationSeconds` | ✗ | ✓ (cũng có trong `AttemptSubmitResponse`) |
| `materials[].mediaUrl` | Đường dẫn ổn định `/api/v1/attempts/{attemptId}/media/{mediaAssetId}`, chỉ học liệu `BEFORE_SUBMIT` | ✓ |

**Media (T2 O2, S11 sửa bởi T2):**
- `GET /api/v1/attempts/{attemptId}/media/{mediaAssetId}` kiểm quyền, kiểm `attempt_media_refs`, rồi trả `302` tới URL R2 ký **5 phút**.
- Không có `mediaUrlExpiresAt`.
- Khi URL hết hạn, trình duyệt gọi lại đường dẫn ổn định để lấy URL mới. Bài làm dài bao lâu cũng không bị hết hạn media.
- `<audio>`/`<img>` không đặt thuộc tính `crossorigin`.

**Lựa chọn P1/P2:** `options[].body = null` ở mọi DTO. Văn bản lựa chọn chỉ nằm ở `review.optionTranscripts` sau khi nộp.

Không bao giờ có trong response: `meta`, `sourceOptionId`, `score`, `scaledScore`, `toeicScore`, `listeningScore`, `readingScore`, `estimated*` (ADR-11).
