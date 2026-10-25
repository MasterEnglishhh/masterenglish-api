# Duyệt tổng hợp phần database còn mở — B01–B37

Ngày: 25/10/2026. Trạng thái: ĐÃ DUYỆT TOÀN BỘ B01–B37.

Người dùng xác nhận: "Ok chốt đi" sau bản duyệt tổng hợp. Từ thời điểm này,
các phương án B01–B37 dưới đây là quyết định đã duyệt; các chữ "đề xuất" còn
trong nội dung được giữ để truy vết bản trình duyệt. Hồ sơ triển khai thiết kế
được hợp nhất trong APPROVED_BASELINE.md, DATA_DICTIONARY.md, ERD.md và
proposals/approved-baseline.sql; không tự áp dụng SQL vào database đang dùng.

Người dùng yêu cầu dừng việc hỏi từng bảng/cột một. Tài liệu này gom toàn bộ
quyết định còn mở đã nhận diện từ REVIEW_OPEN_ISSUES.md, SQL đề xuất cũ và các
luồng UI, có phương án cụ thể để chấp thuận hoặc sửa theo mã. Các D01/D02 và
D03–D29 đã được duyệt giữ nguyên; không đưa chúng ra hỏi lại. D30 chưa duyệt
được nhập vào B01. Toàn bộ các điểm dưới đây đã được xác nhận trong lượt duyệt trên.

Lượt duyệt đã hoàn tất, không cần người dùng xác nhận lại từng mã.
Sau lượt này sẽ tự hoàn thiện ERD/data dictionary/ràng buộc/SQL đề xuất theo
quyết định, không tách thêm vòng hỏi riêng cho từng trường, index hay tên bảng.
Chỉ cần hỏi lại nếu phát hiện nghiệp vụ mới hoặc thay đổi phạm vi đáng kể.
Không tự chạy migration hay đổi database đang sử dụng trong lượt rà soát này.

## Phần giữ nguyên đã duyệt

- Nhiều role/user; ba role độc lập LEARNER/CONTENT_ADMIN/SYSTEM_ADMIN.
- first_name/last_name riêng, mỗi trường tối đa 30 ký tự; profile có phone/avatar;
  email cố định. BCrypt; mật khẩu tối thiểu 15 ký tự, tối đa 72 byte UTF-8.
- Đăng ký/đổi/quên mật khẩu bằng OTP theo các giới hạn và vòng đời đã chốt;
  phiên cố định 7 ngày, access tối đa 15 phút, refresh rotation và audit.
- Media, yêu cầu upload, giới hạn định dạng/dung lượng, quota và giữ/dọn D21–D27.
- Nguồn và liên kết nguồn theo revision, snapshot nguồn D28/D29.
- Mỗi câu thuộc tối đa một nhóm ngữ cảnh, nhóm dùng lại được trong nhiều bộ;
  học liệu chung/riêng có thứ tự, ba loại hiện tại text/ảnh/audio.
- AI làm sau. Video kết hợp nhập chat là hướng mở rộng, không phải tính năng
  đã có trong bản đầu. Số 44 bảng của bản cũ không phải mục tiêu bắt buộc.

## A. Nội dung và phiên bản

| Mã | Điểm cần duyệt | Phương án đề xuất |
|---|---|---|
| B01 | Định danh và phiên bản | content_items có ID ổn định, loại nội dung, người tạo, published_revision_id, is_archived, version và timestamps. content_revisions giữ từng bản có version_no, schema_version, snapshot, trạng thái, tóm tắt thay đổi, người tạo/duyệt, ghi chú duyệt, thời điểm và version chống ghi đè. Một item có nhiều bản nhưng tối đa một bản đang biên soạn/chờ duyệt; người học đọc bản công bố hợp lệ. |
| B02 | Biên soạn/duyệt/công bố | DRAFT → IN_REVIEW → APPROVED → PUBLISHED; bị từ chối thì REJECTED, sửa rồi gửi duyệt lại. CONTENT_ADMIN dùng chung thư viện và được tự duyệt trong bản đầu; vẫn tách duyệt với công bố, không thêm role Reviewer. Từ chối phải có lý do. Sửa bản đã duyệt phải trở lại Draft và duyệt lại; bản đã công bố bất biến, sửa bằng bản mới. Mọi chuyển trạng thái có audit. |
| B03 | Nội dung phụ thuộc | Khi công bố, ghim revision cụ thể của các nội dung được dùng và media/nguồn hợp lệ. Cập nhật từ/câu hỏi con không âm thầm đổi bản deck/nhóm/bộ luyện cũ. Muốn nhận nội dung phụ thuộc mới thì tạo và công bố bản cha mới. Tạo lượt làm tiếp tục ghim đúng bộ revision đã chọn. |
| B04 | Lưu Draft, dữ liệu công bố và media | Draft/snapshot có schema theo từng content_type và schema_version; bảng từ vựng/Grammar/câu hỏi giữ dữ liệu quan hệ của bản công bố để truy vấn. Công bố đồng bộ snapshot, dữ liệu quan hệ và pointer trong transaction. Text dùng Markdown/khối có cấu trúc được kiểm soát. Thêm revision_media_refs và attempt_media_refs có FK để tra cứu đủ file trong JSON trước khi dọn. |
| B05 | Archive, thu hồi và quyền nội dung | Archive ngừng cấp nội dung vào bài mới, giữ bản cũ và lịch sử. Không xóa dây chuyền tiến độ/kết quả. Trường hợp buộc ngừng truy cập vì quyền nội dung phải có thao tác thu hồi riêng, lý do/audit; giữ dữ liệu kết quả nhưng có thể chặn phần học liệu không còn được phép xem. SYSTEM_ADMIN không tự đọc bài làm riêng hoặc biên soạn nếu thiếu role tương ứng. |

## B. Câu hỏi và bộ luyện

| Mã | Điểm cần duyệt | Phương án đề xuất |
|---|---|---|
| B06 | Ý nghĩa question type | Tách category/TOEIC Part, kiểu luyện, cách trả lời và nhóm ngữ cảnh. Có catalogue question_types cho mã/nhãn cách trả lời; câu có group_id tùy chọn, thứ tự câu trong nhóm. Một nhóm là ngữ cảnh cha, các câu con có đáp án riêng. Không dùng một enum để gộp mọi chiều. |
| B07 | Dạng trả lời bản đầu | Kích hoạt SINGLE_CHOICE: chọn một đáp án; điền từ bằng lựa chọn cũng dùng cơ chế này. Các kiểu Anh–Việt, Việt–Anh, nghe chọn từ và Grammar điền lựa chọn là kiểu luyện. Nhập tự do, chọn nhiều, ghép cặp, sắp xếp và chat để sau; cấu trúc có response/schema version để mở rộng, không cần triển khai renderer/grader cho các dạng đó ngay. |
| B08 | Quy tắc TOEIC | Bản đầu luyện Part 1–7 theo nhóm, không mô phỏng đề thi đủ 200 câu. Part 2 có ba lựa chọn, các Part còn lại bốn; Part 3/4 có ba câu/nhóm, Part 6 có bốn câu theo passage. Part 7 hỗ trợ một đến ba tài liệu theo template. Practice cho nghe lại/tua; transcript/đáp án/lời giải chỉ sau nộp. Part 1/2 không lộ phương án chữ/transcript qua payload trước nộp. |
| B09 | Nhóm, học liệu và vị trí trống | Giao nguyên nhóm và giữ thứ tự câu con, không cắt nhóm để đủ số câu. group_materials và question_materials có thứ tự, text body hoặc media_id theo loại. Part 6 có blank_key ổn định, mỗi vị trí trống trỏ đúng một câu và xuất hiện đúng một lần trong material. Câu con kế thừa học liệu nhóm và được thêm material riêng như đã duyệt. |
| B10 | Tạo bộ luyện | Có FIXED và DYNAMIC; một lượt thuộc một category. Vocabulary chọn deck/kiểu; Grammar chọn một hoặc nhiều topic; TOEIC chọn Part; có nguồn MISTAKES. Mỗi lượt 1–100 câu con, chỉ lấy nội dung đủ điều kiện công bố. Thiếu số lượng hoặc không ghép đủ nhóm thì báo số lượng hợp lệ để chọn lại, không tự phá nhóm/lấy ngoài bộ lọc. Giữ thứ tự option và câu trong nhóm; không tự đảo lựa chọn nghe TOEIC. |

Chi tiết phân loại được đề xuất cho câu hỏi: category VOCABULARY/GRAMMAR/TOEIC,
part_number nếu TOEIC, question_type_code/cách trả lời, practice_mode, difficulty,
group_id/group_position nếu có, blank_key nếu có. Các liên kết word_sense/topic
là N:N để báo cáo kiến thức. Một revision có schema đáp án tương ứng với type;
SINGLE_CHOICE phải có đúng một đáp án đúng khi công bố. Vocab/Grammar cho 2–6
lựa chọn, mặc định 4; giới hạn riêng TOEIC như B08. Draft được thiếu dữ liệu,
nhưng không được công bố thiếu đáp án/ngữ cảnh/phân loại cần thiết.

Quy tắc TOEIC đối chiếu tài liệu chính thức IIBC/ETS; thiết kế các lớp tương tác
tham khảo QTI, không yêu cầu triển khai toàn bộ QTI/XML:

- https://www.iibc-global.org/english/toeic/test/lr/about/format.html
- https://www.imsglobal.org/spec/qti/v3p0/guide

## C. Từ vựng, SRS và Grammar

| Mã | Điểm cần duyệt | Phương án đề xuất |
|---|---|---|
| B11 | Từ, nghĩa và deck | Một word có nhiều word_senses; POS/CEFR/nghĩa/ví dụ gắn với sense. Một nghĩa có nhiều ví dụ qua word_sense_examples, audio theo từ hoặc ghi đè ở nghĩa. Deck chứa các nghĩa theo thứ tự, cùng nghĩa không trùng trong một deck; một nghĩa dùng ở nhiều deck. Không ép unique tuyệt đối lemma làm mất các trường hợp trùng từ hợp lệ. |
| B12 | Sửa nội dung và giữ ID | Sửa lỗi trình bày/typo giữ ID, tạo revision mới. Đổi hẳn nghĩa từ, mục tiêu câu hỏi hoặc ngữ cảnh mang ý nghĩa khác thì tạo ID mới, archive bản cũ; không gán nhầm SRS/mistake cũ vào nội dung mới. Sửa đáp án sai của câu hiện có bằng revision mới, không tự chấm lại lịch sử. |
| B13 | Lưu từ và SRS | Lưu/ôn theo user + word_sense, bỏ lưu không xóa tiến độ. Ba nút Quên/Khó/Đã nhớ sau khi lật thẻ; thuật toán dựa SM-2 có version và tham số do backend quản lý; Quên có lịch học lại ngắn. Mỗi review có request_id/hash để retry không cộng hai lần. Lưu cả revision đã ôn và trạng thái/lịch trước-sau để truy vết; đóng tab không tự đánh giá. Không thêm deck cá nhân hoặc lịch sử phiên Flashcard bền vững trong bản đầu; từng review đã ghi vẫn giữ. |
| B14 | Grammar và hoàn thành | Topic có nhiều lesson; lesson thuộc một topic, có thứ tự và nội dung khối text/ví dụ/ảnh/audio. Hoàn thành do người học bấm, không suy ra từ cuộn trang; chưa có row là NOT_STARTED, có IN_PROGRESS/COMPLETED. Giữ revision đã hoàn thành; có bản mới thì báo có cập nhật, không xóa thành tích cũ. Hoàn thành bài không đồng nghĩa làm quiz tốt. |

Mặc định kỹ thuật cho SRS: triển khai chính sách SM2_3BTN_V1, EF khởi đầu 2.5,
tối thiểu 1.3; ánh xạ Quên/Khó/Đã nhớ sang q=1/3/5, dùng công thức và khoảng
ngày của SM-2 cho lần nhớ đạt. Quên reset chuỗi nhớ và đưa thẻ về sau 10 phút
thay vì chỉ đợi lịch ngày — đây là điều chỉnh của dự án, không tuyên bố nguyên
bản SM-2 có ba nút. Hàm chuyển trạng thái sẽ được đặc tả/kiểm thử đầy đủ với
policy version trước code; không cần hỏi lại từng hệ số như quyết định nghiệp vụ.
Nguồn thuật toán: https://super-memory.com/english/ol/sm2.htm

## D. Lượt làm, đáp án, chấm điểm và luyện lỗi

| Mã | Điểm cần duyệt | Phương án đề xuất |
|---|---|---|
| B15 | Nguồn lượt làm và snapshot | attempts giữ source_type, cấu hình chọn, source IDs có thể kiểm tra FK và hash yêu cầu. Khi bắt đầu chốt câu/nhóm/material/option/key/lời giải/revision; resume không bốc câu lại. Snapshot nhóm dùng chung, câu con trỏ vào nhóm snapshot. Key/lời giải/transcript ở dữ liệu riêng tư, DTO trước nộp không trả chúng. Cùng Idempotency-Key + payload trả cùng attempt; khác payload báo xung đột. |
| B16 | Lưu đáp án và nhiều tab | Một đáp án hiện tại cho mỗi câu snapshot, có version chống ghi đè. Chọn và bỏ chọn đều được lưu. Request cũ đến muộn hoặc tab khác xung đột thì báo và tải trạng thái server, không tự hợp nhất. Chỉ chủ lượt làm được đọc/sửa; đổi thiết bị tiếp tục phần đã được server lưu. Submit chốt đáp án trong transaction, sau nộp không cho sửa. |
| B17 | Thời gian bài luyện | Mặc định không đếm ngược. Bộ có time_limit thì server giữ deadline, không gia hạn khi tải lại/đổi thiết bị; hết hạn tự nộp với đáp án đã lưu và lý do TIME_LIMIT, kể cả tác vụ xử lý chạy muộn. Thời gian kết thúc hiệu lực là deadline, không cộng thời gian backend bị trễ. Hiển thị thời gian làm bài riêng với phút học hoạt động. |
| B18 | Bài đang làm/bỏ dở | Tối đa 5 lượt IN_PROGRESS/user. Được tiếp tục hoặc chủ động bỏ; bỏ không tạo điểm giả. Bài không giới hạn giờ, không hoạt động 7 ngày chuyển ABANDONED. Bài có deadline ưu tiên tự nộp theo B17. Nút tạo bài mới không âm thầm bỏ bài đang làm để vượt giới hạn. |
| B19 | Chấm điểm | Backend chấm từ key snapshot; mỗi câu con đúng 1 điểm, sai/bỏ trống 0, không trừ điểm. Nhóm không có điểm cộng riêng. Tỷ lệ đúng = số đúng/tổng câu, có số đúng/sai/bỏ trống; thời gian tính từ server. Không tự quy đổi bài ngắn ra điểm TOEIC chính thức. Submit lặp trả cùng kết quả, không cộng thống kê/lỗi hai lần. |
| B20 | Trạng thái lỗi | Sai hoặc bỏ trống khi bài đã nộp tạo/cập nhật lỗi. OPEN sau lỗi; đúng một lần → IMPROVING; đúng hai lượt làm khác nhau liên tiếp → RESOLVED; sai lại reset chuỗi và về OPEN. Lưu số lần sai, chuỗi đúng, lần gần nhất và nguồn snapshot; không xóa lịch sử sai khi đã giải quyết. |
| B21 | Luyện lại lỗi | Dùng phiên bản hiện hành đủ điều kiện, giữ nhóm nguyên vẹn và thông báo số câu thực tế trước bắt đầu. Chọn một câu sai trong nhóm vẫn luyện cả nhóm và chấm các câu thực trả lời. Một lượt chỉ một category; nguồn đã archive/không còn được cấp thì báo và loại khỏi bài mới, vẫn giữ kết quả cũ theo quyền. |
| B22 | Lịch sử kết quả | Bài đã nộp, key/lời giải và dữ liệu chấm bất biến trong bản đầu; sửa ngân hàng không đổi điểm cũ. Lỗi thống kê/notification không rollback kết quả. Chưa thêm chức năng chấm lại hàng loạt; nếu thêm phải có phiên bản kết quả/audit. Giữ kết quả đã nộp, review SRS và tham chiếu cần thiết trong vòng đời tài khoản, không tự xóa theo mốc 30 ngày của auth/upload. |

Chi tiết dữ liệu lượt làm: source_type gồm PRACTICE_SET, DECK, GRAMMAR_TOPIC,
MIXED_GRAMMAR và MISTAKES. source_config có schema version, nguồn/filter/seed
nếu cần và revision đã chọn; nguồn nhiều topic giữ bằng cấu trúc có kiểm tra
IDs, không dùng practice_set_id làm nguồn duy nhất. source ID và snapshot ID
không nhận tùy ý từ client để chấm bài của người khác.

attempt_answers giữ selected_option_id/response payload theo type, version và
thời điểm; option phải nằm trong snapshot của chính câu. Empty response là bỏ
trống hợp lệ; is_correct/score chỉ backend ghi sau nộp. Thao tác lưu đáp án và
submit phối hợp khóa/trạng thái để không có đáp án đến muộn lọt qua kết quả.

## E. Mục tiêu và thống kê

| Mã | Điểm cần duyệt | Phương án đề xuất |
|---|---|---|
| B23 | Mục tiêu | Một mục tiêu hiện tại/user có LEARNER: điểm TOEIC 10–990 bội 5, phút/ngày 5–240, ngày đích tùy chọn. Có thể bỏ qua. Chưa thêm lịch sử mục tiêu; không tính lại thành tích ngày cũ theo mục tiêu mới. Mục tiêu không phải điểm trình độ được hệ thống dự đoán. |
| B24 | Phút học | Có study_sessions ghi thời gian hoạt động hợp lệ, không lấy thời gian mở tab hoặc last_active_at của phiên đăng nhập. Heartbeat khoảng 15 giây, một lần chỉ ghi nhận tối đa 30 giây và chỉ khi màn hình học đang hoạt động; audio đang phát trong màn hình học được tính. Một user chỉ được cộng một dòng thời gian khi dùng nhiều tab/thiết bị. Đây là ước tính hoạt động, không thu nội dung gõ phím. |
| B25 | Ý nghĩa chỉ số | Nghĩa đã ôn = nghĩa có review thành công; lượt ôn = review được ghi, không trộn với số nghĩa duy nhất. Streak là ngày có hoạt động học hợp lệ. Kỳ ngày theo múi giờ người dùng, lưu thời gian gốc UTC. Chỉ gợi ý điểm yếu topic/Part khi có ít nhất 20 câu đã chấm trong 30 ngày gần nhất và tỷ lệ đúng dưới 70%; luôn hiện cỡ mẫu/kỳ, thiếu mẫu báo chưa đủ dữ liệu. |

study_sessions có user, ngữ cảnh, thời điểm bắt đầu/nhịp ghi nhận/kết thúc,
active_seconds, timezone/local_day và version/credit token để chống cộng trùng.
Chia đoạn khi qua ngày địa phương/đổi ngữ cảnh/múi giờ để tổng hợp chính xác;
server tính thời gian, không tin active_seconds do client gửi. Không suy ra
đã học chỉ từ trang được tải hoặc cộng toàn bộ khoảng ngắt mạng.

## F. Thông báo và import

| Mã | Điểm cần duyệt | Phương án đề xuất |
|---|---|---|
| B26 | Kênh/lịch nhắc | In-app bật, email nhắc học mặc định tắt và người dùng bật riêng. Một giờ nhắc/ngày do người dùng chọn; chưa chọn thì không tự gửi lịch nhắc. Timezone mặc định Asia/Ho_Chi_Minh, giờ yên lặng tùy chọn và hỗ trợ qua nửa đêm. Nhắc học chỉ cho tài khoản có LEARNER. Tắt nhắc không xóa thông báo cũ; chưa có browser push/chiến dịch quảng bá. |
| B27 | Gửi và chống trùng | Dedupe theo user + loại/sự kiện/ngày/ngữ cảnh, delivery riêng từng kênh. PENDING/PROCESSING/SENT/FAILED, có claim/lease và phục hồi khi crash. Một lần gửi đầu + tối đa ba lần thử lại sau 1/5/30 phút. Ghi provider_message_id nếu có; không cam kết email không thể trùng tuyệt đối khi nhà cung cấp đã nhận nhưng phản hồi bị mất. Ghi event cùng transaction nghiệp vụ, xử lý notification sau bằng cơ chế event hiện có. |
| B28 | Import | Vocabulary CSV, câu hỏi/nhóm JSON, Grammar JSON; tối đa 5 MiB và 5.000 dòng/item mỗi job. File đi đường upload riêng tư cho import, không giả làm IMAGE/AUDIO. Có DryRun, lỗi theo dòng/trường, cho kết quả thành công một phần. Dòng hợp lệ tạo Draft, không tự công bố. CONTENT_ADMIN thực hiện theo quyền. |
| B29 | Retry/import trùng | import_job_rows lưu từng dòng với input hash, trạng thái, kết quả entity và lỗi; unique(job,row), tạo entity và ghi thành công atomic. File cập nhật muốn nhận diện xuyên job dùng namespace + external_key; không tự ghép theo lemma/tên. Mặc định create-only, trùng khóa báo/skip; không tự ghi đè bản đã công bố. Retry chỉ xử lý phần chưa thành công. |

Import dùng import_jobs làm ngữ cảnh upload/xử lý, có input file private,
declared/max size, upload expiry, process state/lease và counter; không bắt file
CSV/JSON phải có media_assets IMAGE/AUDIO. import_job_rows thay vai trò bảng
import_job_errors riêng để một bản ghi dòng vừa chống trùng vừa lưu lỗi/kết quả.
Các bộ đếm job phải khớp row ledger. Khoá namespace/external_key thuộc nguồn
nhập/loại nội dung, xác thực quyền và payload; không dùng key để sửa nội dung
ngoài quyền. Thử lại nguyên yêu cầu tạo job cần request_id/hash để trả lại job
cũ khi phản hồi mạng mất.

Delivery giữ lease 60 giây có gia hạn và claim token giống nguyên tắc upload;
không giữ transaction khi gọi nhà cung cấp email. Dedupe trong DB không thay
cho idempotency của nhà cung cấp, nếu có. Lỗi gửi không xóa thông báo in-app hoặc
kết quả học. Dùng event_publication của Modulith, không thêm event registry thứ hai.

## G. Quản trị, retention và hoàn thiện kỹ thuật

| Mã | Điểm cần duyệt | Phương án đề xuất |
|---|---|---|
| B30 | Cấu hình và audit | system_settings có key allowlist, giá trị kiểu rõ, validation/đơn vị và người sửa; chỉ SYSTEM_ADMIN đổi cấu hình được phép. Secret vẫn ở cấu hình môi trường/kho secret. Audit thao tác nội dung, nguồn, review/publish, import quan trọng và settings bằng bảng chung đã duyệt, có before/after/lý do phù hợp. Không cấp quyền xem lịch sử học cá nhân chỉ từ role quản trị user. |
| B31 | Retention phần còn lại | Thông báo giữ 90 ngày; delivery/import metadata và lỗi giữ 30 ngày sau kết thúc; file import tạm đủ 24 giờ sau job kết thúc mới dọn. Bài ABANDONED đủ 30 ngày mới dọn phần chưa tạo kết quả, sau kiểm tra tham chiếu; giữ kết quả đã nộp, SRS, tiến độ và nội dung lịch sử cần thiết. Các mốc auth/media/audit đã duyệt không đổi. |
| B32 | Validation/API | Backend kiểm tra mọi đầu vào: số lượng, enum, length, ID/ownership, payload type và giới hạn JSON/import/file. DTO người học không trả key/transcript sớm hay metadata nội bộ. Phân trang mặc định 20, tối đa 100, sort/filter allowlist; lỗi theo ProblemDetail đã có. Không tin client gửi score/is_correct/role/status/active_seconds hoặc object_key. |
| B33 | Quy ước và truy vấn | Một PostgreSQL, UUID cho tài nguyên, khoá ghép cho bảng nối, FK/unique/CHECK cho ràng buộc phù hợp; UTC/Instant và IANA timezone cho lịch cá nhân. Chọn index từ query thư viện/tìm kiếm/CEFR-POS/Part-topic/đến hạn/lịch sử/job claim; chưa thêm partition, Redis hoặc cache số liệu chỉ vì dự đoán. Những chi tiết kỹ thuật này được tự hoàn thiện từ nghiệp vụ đã duyệt. |
| B34 | Migration và backup | Trước triển khai kiểm tra Flyway history thực tế; không sửa migration đã áp dụng hoặc xóa dữ liệu để khớp thiết kế. SQL đề xuất được kiểm thử trên DB cô lập. Mặc định vận hành dự kiến backup DB hằng ngày, giữ 7 ngày, có bản sao/manifest file tương ứng và thử phục hồi; không xóa media còn cần cho bản backup giữ lại. Không chạy restore/xóa hay triển khai EC2 chỉ vì duyệt tài liệu. |
| B35 | Phạm vi bàn giao | Sau duyệt, làm một ERD + data dictionary + SQL đề xuất thống nhất và kế hoạch migration; tự xử lý tên cột/index, quan hệ và constraint theo gói này. Chưa triển khai AI, video/chat, nhập đáp án tự do, Speaking/Writing, đề full 200 câu, deck cá nhân hoặc bộ máy permission động. Các hướng này được mở rộng có migration/renderer/grader khi có yêu cầu mới, không ghi nhận là đã có. |

## H. Các điểm trước đây còn ghi chờ chốt trong Account/Media

Các mục này bổ sung những lựa chọn trước đây chưa quyết định; không hỏi lại
thời hạn phiên/OTP, cách đăng xuất hoặc hạn mức media đã duyệt.

| Mã | Điểm cần duyệt | Phương án đề xuất |
|---|---|---|
| B36 | Phần tài khoản còn mở | Tối đa 5 phiên hợp lệ/user; đăng nhập mới khi đủ 5 thì thu hồi phiên được tạo lâu nhất. Chưa làm trang quản lý thiết bị/phiên riêng trong bản đầu. Reset password chỉ hoàn tất cho ACTIVE đã xác minh; LOCKED chờ hết hạn/được mở, DISABLED cần quản trị mở; OTP không mở khóa. Gửi email báo đổi/reset mật khẩu thành công, không chứa secret, độc lập với lựa chọn email nhắc học. |
| B37 | Mặc định kỹ thuật còn lại | BCrypt cost khởi điểm 12, đo trên máy triển khai và không hạ dưới 10. Giới hạn IP khởi điểm: 300 yêu cầu gửi OTP/giờ, 300 lần thử OTP/15 phút, 300 lần thử đăng nhập/15 phút; giới hạn theo email đã duyệt vẫn áp dụng riêng. Ngân sách vùng tạm 1 GiB toàn hệ thống, phải giữ chỗ/đối chiếu file tạm và bản sao đang chờ dọn, không chỉ đếm media READY. Giới hạn/bộ đếm ở backend, có cấu hình và thông báo chờ phù hợp. |

Giới hạn IP chỉ dùng IP đã được xác định qua proxy tin cậy, không tin header
client tự đặt. Đây là ngưỡng kỹ thuật khởi điểm có thể điều chỉnh theo tải/NAT,
không đổi chính sách 5 lần sai theo email hoặc tự LOCKED account vì một IP bị
giới hạn. Endpoint công khai giữ phản hồi nhất quán với email không tồn tại.
Đếm mọi lần gọi có thể gây chi phí/kiểm tra mật khẩu phù hợp, không chỉ các
lần gửi email thành công. Không xóa bộ đếm khi client đổi challenge/session ID.

Ngân sách tạm khác quota thư viện 50 MiB/10 GiB đã duyệt: bao gồm chỗ cấp cho
upload, file lỗi/mồ côi và bản sao đang xử lý trước khi được giải phóng sau
đối chiếu/xóa. Khi hết ngân sách, từ chối cấp upload/copy mới với lý do phù
hợp; không xóa file lịch sử để giải phóng chỗ. Cơ chế giữ chỗ/thanh toán byte
và dữ liệu cần thiết sẽ được hoàn thiện trong schema kỹ thuật, không dựa vào
một phép SUM thiếu phối hợp hoặc giả định object storage có hard quota mặc định.

Mặc định validation sẽ được chốt trong data dictionary thay vì hỏi tiếp từng
cột: tên nội dung 200 ký tự, slug 120, lemma/IPA 150; prompt 10.000, lời giải
20.000, material text 50.000, bài Grammar 100.000; JSON API thường tối đa 1 MiB,
import theo B28, file theo D24. Các giới hạn đã duyệt như tên user 30, email 254,
tên nguồn 255, BCrypt 72 byte giữ nguyên. Body/snapshot luôn có schema theo loại,
giới hạn danh sách/nesting và phiên bản; không dùng JSON tự do để né validation.

## Phụ lục — cấu trúc dữ liệu sẽ hoàn thiện sau duyệt

Đây là bản đồ dữ liệu cho các quyết định B, không phải SQL đã áp dụng. Cột ID,
FK, timestamp, trạng thái, version và constraint được bổ sung nhất quán khi viết
data dictionary; không mở thêm vòng duyệt riêng cho từng trường.

| Nhóm | Bảng/dữ liệu chính dự kiến |
|---|---|
| Content | content_items như B01; content_revisions gồm item, version_no, schema_version, snapshot, status, change_summary, created_by, reviewed_by, review_note, reviewed_at, published_at, version, created_at/updated_at; revision_media_refs(revision_id,media_id). |
| Source/media | Giữ content_sources, content_source_links, media_assets và media_upload_requests đã duyệt; bổ sung hợp đồng snapshot/refs để dọn file an toàn. |
| Vocabulary | words; word_senses với POS/CEFR/nghĩa/audio; word_sense_examples với EN/VI/thứ tự; decks; deck_words theo sense; user_saved_words; user_word_progress; word_reviews với request hash/revision/policy/trạng thái trước-sau. |
| Grammar | grammar_topics, grammar_lessons và lesson_progress(user,lesson,state,last_completed_revision_id,timestamps); body là khối có schema. |
| Question | question_types(code,name,schema_version,enabled); questions gồm category/type/practice_mode/Part/difficulty/group_id/group_position/blank_key; question_options; question_groups; group_materials; question_materials; question_words và question_topics. Không giữ group_questions N:N trái quyết định D01. |
| Practice | practice_sets, set_items trỏ question XOR group; cấu hình FIXED/DYNAMIC có schema, số lượng/filter được kiểm tra và không cắt nhóm. |
| Results | attempts với source config/FK/request hash/deadline/submit reason/version; attempt_group_snapshots; attempt_question_snapshots trỏ nhóm nếu có; attempt_answers có version/payload đúng type; attempt_media_refs; mistake_records có wrong_count/consecutive_correct/state/source snapshot. |
| Learning | learning_goals hiện tại; study_sessions ghi hoạt động không cộng trùng; thống kê lấy dữ liệu gốc, chỉ thêm projection tổng hợp nếu đo cho thấy cần. |
| Notifications | notification_preferences, reminder_rules, notifications, notification_deliveries với dedupe/lease/claim/retry/receipt và retention. |
| Import | import_jobs và import_job_rows; mapping namespace/external_key tới item bằng ràng buộc duy nhất phù hợp, không tự ghi đè published. |
| Administration | system_settings, audit_logs đã duyệt, event_publication đúng schema Modulith; không tạo bảng registry/log trùng chỉ vì đổi tên module. |

Snapshot là nguồn dữ liệu bất biến của revision/lượt làm; dữ liệu quan hệ công
bố là projection truy vấn. Mỗi snapshot có schema xác định, ID option/material/
blank ổn định và danh sách media tham chiếu; FK bảo vệ định danh, service bảo
vệ trạng thái/ownership/tương thích. SQL thuần không thay được kiểm tra toàn bộ
nghiệp vụ liên bảng hoặc payload snapshot.

## Đối chiếu các vấn đề cũ để không bỏ sót

| Mục cũ | Được xử lý trong gói |
|---|---|
| D30 chưa duyệt, Draft/Published và quyền biên soạn | B01–B05 |
| DB01/DB02 — type và cấu trúc đáp án | B06/B07/B08/B09/B15/B19 |
| DB03 — nguồn attempt | B10/B15 |
| DB04 — tạo lặp | B15/B29 |
| DB05 — autosave nhận cũ | B16 |
| DB06 — snapshot nhóm/material/key | B04/B08/B09/B15 |
| DB07 — reference media lịch sử | B03/B04/B05/B22, giữ D26 |
| DB08 — retry import | B28/B29 |
| DB09 — mỗi câu tối đa một nhóm | Giữ D01; B06/B09 chuyển cấu trúc cho đúng |
| DB10 — ý nghĩa thống kê/phút học | B23–B25 |
| POL01/POL02/POL03/POL04/POL05 | B12/B14/B20/B17/B03 |
| POL06 — retention | Giữ D19/D26; B22/B31/B34 bổ sung phần còn lại |
| POL07 — worker notification | B27 |
| POL08 — queries/index/quy mô | B32/B33, các hạn mức cấu hình của gói |

## Ghi nhận thực hiện sau khi người dùng duyệt

1. Đã ghi nhận người dùng duyệt toàn bộ B01–B37 ngày 25/10/2026.
2. Đã hợp nhất thành [APPROVED_BASELINE.md](APPROVED_BASELINE.md).
3. ERD, data dictionary và hợp đồng dữ liệu/giao dịch nằm trong ERD.md,
   DATA_DICTIONARY.md và SCHEMA_CONTRACTS.md.
4. SQL tham chiếu ở proposals/approved-baseline.sql, kiểm thử cô lập ghi trong
   SCHEMA_VALIDATION.md. Kế hoạch chuyển đổi ở MIGRATION_PLAN.md; lịch sử Flyway
   và dữ liệu DB thật sẽ được đọc khi bắt đầu bước migration thực thi.
5. Chỉ chuyển sang sửa code/migration hoặc triển khai khi bước công việc tương
   ứng được yêu cầu; không đưa thêm vòng phê duyệt cho chi tiết kỹ thuật thông thường.
