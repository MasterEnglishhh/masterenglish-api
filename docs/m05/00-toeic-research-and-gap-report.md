# M05 — Khảo sát TOEIC L&R và gap với SRS/SDD v5.0

Ngày: 26/11/2026. Nguồn đầu vào: `EnglishMaster_SRS_SDD.docx.pdf` (v5.0, 46 trang), code tại commit `4afb631`. Quyết định rút ra từ báo cáo này nằm ở [ADR-07..13](01-adrs-07-to-13.md).

## 1. Phạm vi và quy ước độ tin cậy

- Mọi chi tiết về đề thi có số nguồn `[n]` ở mục 10.
- Hai nguồn chính là tài liệu của ETS, đã đọc toàn văn:
  - Bộ slide cập nhật định dạng (2017) `[1]`.
  - Đề mẫu Listening & Reading bản giấy, có transcript `[2]`.
- Thông tin chỉ có từ blog hoặc trang quảng cáo được ghi là nguồn thứ cấp.
- Điều gì không tìm được nguồn được ghi là **chưa kiểm chứng**. Những điều đó chỉ xuất hiện ở phần phương án.

## 2. Cấu trúc đề chuẩn ETS

- Listening: khoảng 45 phút, 100 câu, 4 Part.
- Reading: 75 phút, 100 câu, 3 Part.
- Mỗi Part có Directions riêng `[2]`. Số câu lấy theo bảng so sánh định dạng cập nhật `[1]`.

| Part | Số câu | Lựa chọn | In trong đề? | Biến thể cần lưu ý |
|---|---|---|---|---|
| P1 Photographs | 6 câu độc lập | 4 câu mô tả (A)–(D), chỉ nói | Ảnh có in. Câu mô tả **không in**, nói một lần `[2]` | Có câu ví dụ kèm khoảng dừng 3 giây `[2]`. Nhãn A–D nằm trong audio, nên thứ tự lựa chọn gắn với file âm thanh. |
| P2 Question-Response | 25 câu độc lập | 3 phản hồi (A)–(C), chỉ nói | Không in, nói một lần `[2]` | Câu mở đầu có thể là câu hỏi hoặc câu trần thuật. |
| P3 Conversations | 39 = 13 × 3 | 4 lựa chọn, có in | Câu hỏi có in, hội thoại **không in** `[2]` | Hội thoại "two or more people", có set "with three speakers" `[1][2]`. Set "conversation and list" kèm câu "Look at the graphic" `[1][2]`. Câu ý định: "Why does the woman say: 'I can't believe it'?" `[1]`. |
| P4 Talks | 30 = 10 × 3 | 4 lựa chọn, có in | Câu hỏi có in, bài nói **không in** `[2]` | Một người nói. Set "talk and program" có câu "Look at the graphic…" `[1][2]`. Câu "What does the man mean when he says…" `[2]`. |
| P5 Incomplete Sentences | 30 câu độc lập | 4 | Có | ETS chỉ mô tả "a word or phrase is missing" `[2]`. Phân loại từ loại/ngữ pháp/từ vựng là quy ước của giới luyện thi, không phải của ETS. |
| P6 Text Completion | 16 = 4 văn bản × 4 | 4 | Có | "A word, phrase, **or sentence** is missing" `[2]`. Có câu chọn nguyên một câu (câu 134 trong ví dụ của ETS `[1]`). Ô trống đánh số theo số câu trong đề ("Questions 131-134") `[2]`. |
| P7 Reading Comprehension | 54 = 29 câu đơn (10 bài, 2–4 câu/bài) + 25 câu đa văn bản (2 bộ đôi, 3 bộ ba, 5 câu/bộ) `[1]` | 4 | Có | Loại văn bản trong đề mẫu: advertisement, article, text message chain, information, "advertisement and e-mail", "advertisement, online shopping cart, and e-mail" `[2]`. Chat có tên người viết và mốc giờ `[1]`. Câu ý định "At 12:15, what does Mr. Otani mean when he writes…" `[1][2]`. Câu chèn "positions marked [1], [2], [3], and [4]" `[1][2]`. Câu NOT `[2]`. |

**Transcript và audio trong tài liệu ETS** `[2]`:
- Nhãn lượt nói: `(N)` cho người dẫn; `(M-Cn)`, `(W-Am)`, `(M-Au)`, `(W-Br)` cho người nói. Slide `[1]` cũng dùng `W-Am:`, `M-Au:`. Cách hiểu M/W là nam/nữ và Am/Br/Cn/Au là giọng Mỹ/Anh/Canada/Úc là suy luận phổ biến, chưa tìm được văn bản ETS định nghĩa.
- Mốc dừng: `[5 seconds]` (P1–P2), `[8 seconds]` sau mỗi câu hỏi P3–P4, và một mốc `[12 seconds]` ở hai set có hình.
- Audio chạy liền trong phòng thi ("spoken only one time"). ETS không công bố cách đóng gói file cho bên thứ ba.

## 3. Chấm điểm và quy đổi

| Đã có nguồn | Nguồn |
|---|---|
| Mỗi kỹ năng 5–495 điểm, bước 5. Tổng 10–990. | `[6]` |
| Điểm trên phiếu không phải số câu đúng. Đó là điểm quy đổi bằng "equating". | `[5]` |
| Không trừ điểm câu sai. Câu bỏ trống có tác dụng như câu sai. | `[6][7]` |
| Phiếu điểm có "Percent Correct of Abilities Measured" (5 năng lực L, 5 năng lực R). Tỷ lệ này không so sánh được giữa các đề. | `[8]` |
| ETS không công bố bảng quy đổi. Các bảng trên mạng đều là ước tính, ví dụ 70/100 Reading tương đương khoảng 320–380. | `[11]` |
| Ứng dụng TOEIC chính thức (ETS Global) có 10 đề thi thử, ghi là *estimated scores* trên thang 990. | `[14]` |
| Cách quy đổi khi làm một Part lẻ trên Study4/Prep/Migii | **Chưa kiểm chứng** |

## 4. Chế độ làm bài

| Khía cạnh | Kỳ thi thật | Luyện Part (nền tảng) | Thi thử Full Test |
|---|---|---|---|
| Audio | Nói một lần, không tua `[2]`. Bản thi trên máy: không có nút chuyển câu ở Listening `[15]` (thứ cấp). | Tua, nghe lại, tốc độ: chưa kiểm chứng | Chưa kiểm chứng |
| Đồng hồ | Listening theo nhịp audio, Reading 75 phút `[2]` | Study4 cho chọn Part và tự đặt giờ, gợi ý ~1 phút/câu `[9]` | Ép giờ, tự nộp: chưa kiểm chứng |
| Điều hướng | Bản trên máy: không quay lại Listening khi đã sang Reading `[15]` (thứ cấp) | Tự do | Chưa kiểm chứng |
| Công cụ | Bản giấy: không ghi lên đề `[2]` | Highlight, gạch đáp án, cắm cờ: chưa kiểm chứng | — |

## 5. Sau khi nộp

- Đã có nguồn:
  - Study4: giải thích, transcript, dịch nghĩa cho từng câu `[9]`; thống kê theo dạng bài `[10]`.
  - Prep: chấm ngay, kèm giải thích `[12]`.
  - Migii: đáp án và giải thích `[13]`.
  - ETS: tỷ lệ đúng theo năng lực `[8]`.
- Chưa kiểm chứng: transcript đồng bộ audio, dịch song ngữ từng câu, tô sáng dẫn chứng P7, lưu từ từ bài đọc.

## 6. Media và nội dung

Không tìm được số liệu công bố về dung lượng đề. Bảng dưới là **phép tính** `dung lượng = bitrate × 2.700 giây`, không phải số đo.

| Bitrate | 45 phút audio | So với giới hạn MP3 ≤ 20 MiB |
|---|---|---|
| 32 kbps | 10,8 MB ≈ 10,3 MiB | Lọt |
| 48 kbps | 16,2 MB ≈ 15,4 MiB | Lọt |
| 64 kbps | 21,6 MB ≈ 20,6 MiB | **Vượt** |
| 128 kbps | 43,2 MB ≈ 41,2 MiB | **Vượt** |

**Phương án cắt audio:**
- Theo câu (P1–P2) và theo set (P3–P4): file nhỏ, khớp `question_groups`. **Đã chọn (ADR-13).**
- Một file liền kèm timestamp: mô phỏng phòng thi tốt hơn, nhưng vượt 20 MiB và không ghép được đề động.

**Hiển thị Part 7:**
- Văn bản có cấu trúc: đọc được trên điện thoại và dark mode, có a11y, đặt được neo. **Đã chọn (R6).**
- Ảnh chụp trang: nhập nhanh, nhưng nặng và không có a11y.

## 7. Tổng kết

**A. Sự thật chuẩn ETS (đã có nguồn)**
- 200 câu theo phân bố ở mục 2. Listening ~45 phút, Reading 75 phút. Câu và đáp án P1/P2, hội thoại P3, bài nói P4 không in.
- P3 có hội thoại ba người. P3 và P4 có câu graphic và câu ý định. P6 có ô điền nguyên câu. P7 có chat, bộ ba văn bản, câu chèn [1]–[4] và câu NOT.
- Thang 5–495, bước 5. Dùng equating. Không trừ điểm câu sai. Không có bảng quy đổi công khai.

**B. Khác biệt giữa các nền tảng (để nhóm tự chọn)**
- Study4 cho luyện từng Part với giờ tự chọn.
- Ứng dụng chính thức, Prep, Migii đều có thi thử. Điểm 990 là ước tính.
- Study4 có giải thích, transcript và dịch. Prep và Migii có giải thích.
- Chưa kiểm chứng ở mọi nền tảng: tốc độ audio, công cụ đánh dấu, quy đổi điểm cho Part lẻ, dung lượng đề.

## 8. Gap SRS v5.0 ↔ thực tế

| Hạng mục | Thực tế ETS / UX | SRS v5.0 | Mức | Rủi ro → Quyết định |
|---|---|---|---|---|
| P1–P2 ẩn nội dung | Không in | NFR-SEC-04, 2.3.1 | Đạt | — |
| P1–P2 thứ tự lựa chọn | Nhãn A–D nằm trong audio | Không nói | Thiếu | Xáo lựa chọn gây chấm sai → `option_order = FIXED` (ADR-08) |
| P3–P4 transcript | Không in, chứa đáp án | NFR-SEC-04 chỉ nêu P1–P2 | Mâu thuẫn | Lộ đáp án → lọc theo `visibility` (ADR-08, A-03) |
| P3–P4 học liệu | Graphic hiện trước, transcript hiện sau | "Có thể kèm hình", Text/Image | Thiếu | `public_snapshot`/`private_snapshot` (ADR-07) |
| Transcript nhiều người | Nhãn `(N)`, `(W-Am)`… và mốc dừng | Không có | Thiếu | Kiểu `TRANSCRIPT` có `segments` (02 §3) |
| P3–P4 "nộp đồng thời" | Trả lời từng câu | Mâu thuẫn với M05.06 | Mâu thuẫn | A-04 |
| P6 `blank_key` | Số ô = số câu trong đề | `[131]` | Thiếu | Khóa ổn định `b1..b4` (A-05) |
| P7 loại tài liệu | Chat, quảng cáo, email, giỏ hàng… | Text/Image | Thiếu | `RICH_TEXT`, `CHAT`, `TABLE` |
| P7 [1]–[4], mốc giờ | Neo nằm trong văn bản | Không có | Thiếu | Node `insert`, `anchor` (R6) |
| Practice/Exam, bỏ dở | Phòng thi theo giờ | Không định nghĩa | Thiếu | ADR-10 |
| Thang điểm | Equating 990 | `correct_count` + % và mục tiêu 990 | Mâu thuẫn | ADR-11 |
| CAS | Request cũ không được ghi đè | `version >= client_version` | Mâu thuẫn | ADR-09, A-02 |
| `is_correct` lúc lưu nháp | Chỉ chấm sau khi nộp | ERD đặt trên answer | Thiếu | `NULL` đến lúc nộp (ADR-09) |
| Ghi `mistake_records` | — | SEQ-04 ghi thẳng, mâu thuẫn 3.1 | Mâu thuẫn | ADR-12, A-10 |
| Snapshot JSON | Định dạng còn đổi | Không có `schema_version` | Thiếu | ADR-07 |
| Số câu | Full Test 200 | V13 giới hạn 1–50 | Thiếu | Nới lên 1–200 |
| Hiệu năng chụp đề | ~1.017 dòng snapshot | Pool 5 kết nối | Thiếu | JDBC batch, benchmark (ADR-13) |
| Kích thước audio | 45 phút | MP3 ≤ 20 MiB | Thiếu | Cắt theo câu/set (ADR-13) |

## 9. Phân loại YAGNI

- **MUST-FIX (đã chốt ở T0):** CAS, ẩn transcript theo `visibility`, khóa thứ tự lựa chọn P1–P2, `mistake_records` qua event, snapshot có `schema_version`, `blank_key` ổn định, thống nhất `SUBMITTED`, ngữ nghĩa số liệu kết quả, cắt audio theo câu/set.
- **EXTENSIBLE-BY-DESIGN (chừa chỗ, chưa làm UI):** `attempt_mode`, `deadline_at`/`time_limit_seconds`/`submit_reason`, `material_type` và `document_kind` mở rộng được, transcript có `start_ms`/`end_ms` tùy chọn, neo trong tài liệu, `question_kind`, số đúng theo kỹ năng.
- **DEFER P1:** giao diện Exam Mode, tốc độ audio, gạch đáp án, highlight, cắm cờ, transcript karaoke, dịch song ngữ, tô sáng dẫn chứng, lưu từ từ bài đọc, quy đổi 990/IRT, luyện lại câu sai (D13), cache audio offline, endpoint `abandon`.

## 10. Nguồn

1. ETS (2017), *Examples of the Updates to the TOEIC® Listening and Reading Test*, bản lưu: https://www.jams.tv/wp-content/uploads/2018/04/pdfs-TOEIC%202018%20Updates%20Detail.pdf (đã đọc toàn văn).
2. ETS, *TOEIC Listening & Reading Test — Paper-delivered sample test*: https://www.mx.ets.org/content/dam/ets-org/pdfs/toeic/toeic-listening-reading-sample-test.pdf (đã đọc toàn văn).
3. ETS, TOEIC Listening and Reading: https://www.ets.org/toeic/about/listening-reading.html (qua kết quả tìm kiếm).
4. IIBC, Test format: https://www.iibc-global.org/english/toeic/test/lr/about/format.html
5. IIBC, Test Results: https://iibc-global.org/english/toeic/test/lr/guide05.html (trích qua kết quả tìm kiếm).
6. ETS (2010), TC-10-03: https://www.ets.org/Media/Research/pdf/TC-10-03.pdf (trích qua kết quả tìm kiếm).
7. ETS, FAQ My score: https://www.eu.ets.org/toeic/about/faq/my-score.html (trích qua kết quả tìm kiếm).
8. IIBC, Score report / Abilities Measured: https://www.iibc-global.org/english/toeic/test/lr/guide05/guide05_01.html
9. Study4, Hướng dẫn học Complete TOEIC: https://study4.com/posts/1251/huong-dan-cach-hoc-khoa-complete-toeic-cua-study4-hieu-qua/
10. Bài chia sẻ người dùng về Study4 (Lemon8, nguồn thứ cấp): https://www.lemon8-app.com/@aiemmon_mika/7273096619875222018?region=vn
11. Prep, Thang điểm TOEIC: https://prepedu.com/vi/blog/thang-diem-toeic
12. Prep, Phòng luyện đề ảo: https://prepedu.com/vi/blog/phong-luyen-de-ao-toeic-test-practice
13. Migii Prep – TOEIC L&R Test (App Store, không được ETS bảo trợ): https://apps.apple.com/us/app/id1615348376
14. ETS Global, TOEIC Test Application: https://www.etsglobal.org/bw/en/content/toeic-test-application
15. mytour.vn, Thi TOEIC trên máy tính (blog thứ cấp): https://mytour.vn/vi/blog/bai-viet/thi-toeic-tren-may-tinh-nhung-thong-tin-can-biet.html

**Chưa kiểm chứng:**
- Hành vi chi tiết của Study4, Prep, Migii.
- Dung lượng đề thực tế.
- Quy định thi trên máy của IIG Việt Nam.
- Sản phẩm "ETS Digital".
