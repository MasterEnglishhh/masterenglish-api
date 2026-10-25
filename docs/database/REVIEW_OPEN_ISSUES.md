# Rà soát database và các quyết định còn mở

Ngày 25/10/2026. Lịch sử thảo luận; quyết định đã được hợp nhất trong
[APPROVED_BASELINE.md](APPROVED_BASELINE.md) sau khi duyệt toàn bộ B01–B37.
Chưa thay migration/entity hoặc áp dụng database dự án. Các đoạn chờ duyệt bên
dưới mô tả thời điểm trao đổi cũ, không thay thế kết luận tổng hợp.

Người dùng muốn củng cố toàn bộ database. Góp ý của giảng viên về question type là
một điểm đầu vào; không giới hạn việc rà soát ở riêng thêm cột hoặc bảng loại câu hỏi.
Con số 44 bảng là số đếm của bản đề xuất cũ, không phải tiêu chí đủ nghiệp vụ.

## Những quyết định đã có

- Mỗi user có thể có nhiều role.
- Giữ phone và avatar trong hồ sơ.
- Tập trung BE/FE; AI làm sau.
- Phạm vi đang đối chiếu: Vocabulary/Flashcard, Grammar, TOEIC Part 1–7,
  Practice/Results, Content/Media, Account, Notifications và Administration.
- Full mock 200 câu, nhập câu trả lời tự do, Speaking/Writing và lịch sử phiên
  Flashcard bền vững chưa tự động được coi là yêu cầu bản đầu.

## Trình tự duyệt lại từ đầu

Điều chỉnh ngày 25/10/2026 theo yêu cầu người dùng: không tiếp tục hỏi từng bảng/
cột một. Toàn bộ phần còn mở được gom ở DATABASE_BATCH_REVIEW.md, mã B01–B37,
kèm phương án để duyệt một lượt. Người dùng đã xác nhận "Ok chốt đi" cho toàn
bộ B01–B37 ngày 25/10/2026. D01/D02 và D03–D29 được giữ nguyên; D30 được duyệt
thông qua B01. Các trạng thái chờ duyệt ở phần lịch sử bên dưới được thay bằng
quyết định tổng hợp này. Sau lượt duyệt tổng hợp, tự hoàn thiện
ERD, data dictionary, ràng buộc và SQL đề xuất theo nghiệp vụ, không hỏi lại từng
chi tiết kỹ thuật thông thường. Giai đoạn này vẫn chưa sửa entity/migration.

Người dùng yêu cầu quay lại duyệt toàn bộ database theo nghiệp vụ, bắt đầu ở
Account/Identity. Thứ tự: tài khoản và phân quyền → học liệu và nội dung → bài tập
và câu hỏi → lượt làm/chấm điểm/kết quả → tiến độ/thống kê → thông báo/quản trị.
Các D01/D02 trước đó được giữ làm quyết định đã có, không dùng để bỏ qua các nhóm
nghiệp vụ đầu tiên. Chỉ cập nhật tài liệu trong giai đoạn này; chưa sửa tiếp entity
hoặc migration.

## D03 Đăng ký phải xác minh email đã được xác nhận

Ngày xác nhận: 25/10/2026, qua trao đổi trực tiếp với người dùng.

- Luồng đăng ký người học yêu cầu xác minh quyền sở hữu email.
- Tài khoản chưa xác minh chưa được dùng các chức năng học yêu cầu tài khoản
  đã kích hoạt; vẫn cần cho phép thực hiện luồng xác minh và gửi lại hợp lệ.
- Xác minh email đăng ký và khôi phục mật khẩu là hai mục đích riêng; yêu cầu
  hoặc bằng chứng xác minh của luồng này không được dùng thay cho luồng kia.
- Người dùng đã chọn nhận OTP qua email và nhập mã trên ứng dụng để xác minh;
  không dùng đường link làm phương thức xác minh đăng ký trong phạm vi đã chọn.
- Đã chốt các thông số OTP và chuyển sang màn hình đăng nhập sau xác minh theo
  bảng chính sách bên dưới; không tự đăng nhập sau bước xác minh đăng ký.
- Đã chốt cách xử lý email đã đăng ký, gồm tài khoản chờ xác minh, đang hoạt động
  và bị khóa/vô hiệu hóa, cùng thời hạn giữ đăng ký dở 7 ngày.
- Chưa chốt tên bảng/cột hoặc việc áp dụng luồng này cho tài khoản admin được cấp.

Dữ liệu cần thiết kế tiếp gồm trạng thái và thời điểm xác minh email, yêu cầu
xác minh có thời hạn và trạng thái đã sử dụng/thu hồi. SQL và entity hiện có chưa
thực hiện D03; cần được cập nhật sau khi duyệt xong nghiệp vụ liên quan.

### Chính sách OTP đăng ký đã được xác nhận

Người dùng xác nhận ngày 25/10/2026: "OK chốt đi" đối với bộ quy tắc sau:

| Nội dung | Đã chốt |
|---|---|
| Định dạng | OTP 6 chữ số ngẫu nhiên, có thể có số 0 ở đầu |
| Thời hạn | 5 phút |
| Gửi lại | Ít nhất 60 giây giữa hai lần; tối đa 5 email/giờ cho một địa chỉ |
| Mã hợp lệ | Chỉ mã mới nhất còn hiệu lực; gửi mã mới vô hiệu mã cũ |
| Nhập sai | Tối đa 5 lần rồi tạm dừng xác minh 15 phút; gửi lại không xóa bộ đếm hoặc thời gian chờ |
| Thành công | Dùng một lần, ghi nhận thời điểm xác minh và kết thúc yêu cầu trong cùng transaction |
| Sau xác minh | Tài khoản đủ điều kiện hoạt động thì chuyển người dùng sang màn hình đăng nhập |
| Phạm vi | OTP gắn đúng email và luồng đăng ký; không dùng cho reset password |

Không kích hoạt lại account đang LOCKED/DISABLED chỉ vì xác minh một mã cũ. Đăng ký
lặp không tạo tài khoản trùng và không tự ghi đè password/profile của yêu cầu trước
khi chưa có bằng chứng sở hữu email. Cách tiếp tục một đăng ký dở đã được duyệt
ở mục đăng ký lặp bên dưới; thời hạn giữ hồ sơ chưa xác minh đã chốt là 7 ngày.

Khi triển khai: sinh mã bằng bộ sinh số ngẫu nhiên mật mã, lưu xác thực mã an toàn
(HMAC có secret server), không lưu/log OTP rõ. Counter, resend và consume cần được
bảo vệ khi có request đồng thời. Các mức thời gian/số lần ở bảng trên là chính sách
dự án đã được người dùng duyệt, không phải thông số bắt buộc từ OWASP. Giới hạn
theo IP là kiểm soát kỹ thuật cần thiết kế thêm; chưa chốt thông số cụ thể.

Tham khảo nguyên tắc mã xác minh ngẫu nhiên, dùng một lần, có thời hạn và kích hoạt
sau xác minh: https://cheatsheetseries.owasp.org/cheatsheets/Email_Validation_and_Verification_Cheat_Sheet.html

### Trạng thái tài khoản đã được xác nhận

Người dùng xác nhận ngày 25/10/2026: "Ok duyệt" đối với bốn trạng thái sau.

| Trạng thái | Ý nghĩa đã chốt |
|---|---|
| PENDING_VERIFICATION | Đã đăng ký, đang chờ xác minh email; chưa được dùng chức năng của tài khoản đã kích hoạt |
| ACTIVE | Email đã xác minh, tài khoản hoạt động bình thường |
| LOCKED | Tạm khóa tài khoản vì lý do bảo mật; điều kiện khóa/mở cần duyệt riêng |
| DISABLED | Quản trị hệ thống vô hiệu hóa tài khoản; chỉ được sử dụng lại khi có quyết định mở hợp lệ |

Thời gian chờ 15 phút do nhập sai OTP chỉ thuộc luồng xác minh, không tự đổi user
sang LOCKED/DISABLED. Khi mở lại một tài khoản chưa xác minh phải trở về luồng
PENDING_VERIFICATION, không tự coi là ACTIVE. Tên và ý nghĩa bốn trạng thái đã được
duyệt; điều kiện gây khóa/mở và quy trình quản trị vẫn cần duyệt riêng. Chưa sửa
vào enum, entity hoặc CHECK constraint của SQL.

### Đăng ký lặp cùng email đã được xác nhận

Người dùng xác nhận ngày 25/10/2026: "Có chốt đi" đối với cách xử lý sau.

| Tình trạng email | Cách xử lý đã chốt |
|---|---|
| Chưa có tài khoản | Tiếp tục luồng đăng ký và OTP đã chốt |
| Đã có tài khoản PENDING_VERIFICATION | Cho tiếp tục đăng ký/xác minh với cùng tài khoản; không tạo user trùng; yêu cầu OTP vẫn chịu giới hạn gửi/thử đã chốt |
| Đã có tài khoản ACTIVE | Không tạo user mới hoặc đổi thông tin qua đăng ký; hướng dẫn chủ email dùng đăng nhập/quên mật khẩu |
| Đã có tài khoản LOCKED/DISABLED | Không tạo user mới, không dùng OTP đăng ký để mở khóa hoặc thay đổi quyền/trạng thái |

Nếu người dùng nhập lại tên/mật khẩu cho một đăng ký chưa hoàn tất, thông tin này
chỉ được xác nhận sau khi chứng minh sở hữu email bằng OTP gắn đúng lượt đăng ký
đó. Không đơn thuần kích hoạt tài khoản với mật khẩu từ một lượt đăng ký khác.
Về dữ liệu cần liên kết rõ email, lượt đăng ký và yêu cầu OTP; chưa quyết định
tên bảng hoặc cách lưu vật lý ở giai đoạn thảo luận này.

OTP hết hạn chỉ kết thúc hiệu lực mã, không tự xóa tài khoản chờ xác minh. Thời
gian giữ đăng ký dở đã chốt ở mục dưới; cách triển khai dọn vật lý sẽ được thiết kế
sau khi duyệt schema. Phản hồi công khai
cần tránh lộ trạng thái tài khoản; hướng dẫn dành cho chủ email có thể được gửi
qua email với giới hạn gửi phù hợp.

### Thời hạn đăng ký dở đã được xác nhận

Người dùng xác nhận ngày 25/10/2026: "Ok đồng ý" đối với thời hạn và phạm vi sau.

- Với đăng ký người học công khai chưa từng xác minh/kích hoạt, giữ hồ sơ
  chờ xác minh trong 7 ngày tính từ lần đăng ký ban đầu.
- Gửi lại OTP không gia hạn thời hạn đăng ký dở. Mỗi mã OTP vẫn chỉ có hạn 5 phút
  và chịu các giới hạn đã chốt.
- Hết 7 ngày, đăng ký dở hết hạn; có thể dọn dữ liệu đăng ký chưa hoàn tất và cho
  chủ email thực hiện đăng ký lại. Cách dọn vật lý sẽ quyết định khi chốt schema.
- Chỉ áp dụng khi chưa từng xác minh/kích hoạt và không có dữ liệu nghiệp vụ cần
  bảo toàn; không áp dụng cho tài khoản đã hoạt động, bị khóa, bị vô hiệu hóa hoặc
  tài khoản admin được cấp.
- Xác minh, hết hạn và dọn dữ liệu phải được xử lý an toàn khi chạy đồng thời;
  tác vụ dọn không được xóa một tài khoản vừa xác minh thành công.

Mốc 7 ngày là chính sách vận hành đã được người dùng duyệt. Đây là xác nhận thiết
kế nghiệp vụ; lượt này chỉ ghi tài liệu, không thực hiện dọn dữ liệu hoặc sửa
migration/entity.

### Phân chia thông tin đăng ký và hồ sơ đã được xác nhận

Người dùng xác nhận số điện thoại và các thông tin bổ sung được cập nhật ở profile
sau đăng ký. Biểu mẫu đăng ký giữ thông tin cơ bản ở bảng dưới.

| Thông tin | Lúc đăng ký / profile | Cách sử dụng |
|---|---|---|
| Họ và tên | Bắt buộc | Nhập riêng first_name và last_name, mỗi trường tối đa 30 ký tự theo điều chỉnh ở D20 |
| Email | Bắt buộc | Định danh đăng nhập và địa chỉ nhận OTP |
| Mật khẩu | Bắt buộc | BCrypt; độ dài tối thiểu 15 ký tự, tối đa 72 byte UTF-8 theo D20 |
| Nhập lại mật khẩu | Bắt buộc trên biểu mẫu | Đối chiếu với mật khẩu, không lưu thành cột database |
| Số điện thoại | Không bắt buộc; bổ sung trong hồ sơ sau | Thông tin liên hệ, chưa dùng để đăng nhập/SMS |
| Avatar | Không bắt buộc; bổ sung trong hồ sơ sau | Chưa đặt thì giao diện dùng ảnh mặc định; ảnh cá nhân liên kết metadata media |

Đã chốt việc bổ sung thông tin hồ sơ sau đăng ký. Email đăng nhập được giữ cố định
trong bản đầu. Ràng buộc độ dài/định dạng và việc tách first_name/last_name được
duyệt ở D20. Cụm "các thông tin khác" chưa tự xác nhận thêm trường cụ thể ngoài những trường đã nêu;
nếu cần bổ sung trường mới sẽ đối chiếu với mục đích sử dụng trước.

### Phạm vi chỉnh sửa profile đã được xác nhận

Hồ sơ cơ bản đang xét gồm first_name, last_name, số điện thoại và avatar. Người dùng chỉnh
sửa dữ liệu của chính mình; không nhận user ID tùy ý để thay đổi hồ sơ người khác.
Role/status thuộc luồng quản trị và bảo mật, không phải trường tự chỉnh trong
profile. Mật khẩu thay đổi qua luồng riêng và người dùng đã yêu cầu xác thực OTP
cho thao tác tự đổi mật khẩu; chi tiết ở D04.

Người dùng xác nhận ngày 25/10/2026: "Giữ cố định đi" đối với email đăng nhập.
Trong bản đầu, profile chỉ hiển thị email đã đăng ký, không cung cấp luồng đổi
email. Không thêm yêu cầu xác minh chuyển email hoặc trường pending email ở giai
đoạn này. Nếu mở rộng sau, sẽ duyệt nghiệp vụ và thiết kế dữ liệu riêng.

## D04 Tự đổi mật khẩu phải xác thực OTP

Ngày ghi nhận: 25/10/2026. Người dùng xác nhận: "Mật khẩu có thể tự đổi nhưng cần
xác thực OTP" trong ngữ cảnh tài khoản/profile sau đăng nhập.

- Cho người dùng tự đổi mật khẩu của chính mình, có bước xác thực OTP bắt buộc.
- OTP cần gắn với đúng user và mục đích PASSWORD_CHANGE, gửi tới email hiện tại
  đã xác minh của tài khoản. Không lấy email nhận mã tùy ý từ request profile.
- Không dùng OTP đăng ký hoặc OTP quên mật khẩu để xác nhận đổi mật khẩu.
- Email đăng nhập được giữ cố định trong bản đầu theo quyết định đã xác nhận.

### Luồng đổi mật khẩu đã được xác nhận

Người dùng xác nhận ngày 25/10/2026: "Duyệt" đối với luồng có mật khẩu hiện tại,
OTP và đăng xuất tất cả thiết bị sau khi đổi thành công.

1. Người dùng có phiên đăng nhập hợp lệ và tài khoản đủ điều kiện hoạt động.
2. Nhập mật khẩu hiện tại, mật khẩu mới và xác nhận mật khẩu mới. Kiểm tra mật khẩu
   hiện tại trước khi gửi OTP; thông tin mật khẩu mới gắn với đúng yêu cầu đổi.
3. Gửi OTP tới email đã xác minh. Dùng cùng thông số 6 chữ số/5 phút, gửi
   lại sau 60 giây/tối đa 5 email mỗi giờ, sai 5 lần chờ 15 phút như luồng đăng ký;
   bộ thông số này đã được duyệt cho đổi mật khẩu. Các mã vẫn tách
   biệt theo mục đích, và gửi lại không đặt lại giới hạn thử sai.
4. Chỉ khi xác thực thành công mới đổi password hash, ghi thời điểm đổi, tăng
   auth_version, consume OTP và thu hồi các phiên/token trong cùng transaction.
5. Đăng xuất tất cả thiết bị, gồm phiên hiện tại, và yêu cầu đăng nhập lại.

Các bước yêu cầu mật khẩu hiện tại, thông số OTP và chính sách đăng xuất ở trên
đã được người dùng duyệt. Không đổi password khi mới yêu cầu OTP;
không dùng thời gian chờ của OTP để khóa toàn bộ tài khoản. Các chi tiết vật lý
sẽ được thiết kế sau khi chốt nghiệp vụ; chưa sửa SQL/entity.

Email thông báo đổi mật khẩu thành công là khuyến nghị bổ sung khi hoàn thiện
luồng; chưa coi là một yêu cầu chức năng mới đã được duyệt. Không gửi password
trong email thông báo.

Tham khảo yêu cầu phiên đăng nhập và kiểm tra mật khẩu hiện tại:
https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html#change-password-feature

## D05 Luồng quên mật khẩu đã được xác nhận

Đây là luồng khôi phục mật khẩu khi không có phiên đăng nhập hoặc không nhớ mật
khẩu hiện tại. Người dùng xác nhận ngày 25/10/2026: "Ok chốt" đối với luồng bên dưới.

1. Nhập email đã đăng ký. Phản hồi công khai thống nhất, ví dụ: "Nếu email đủ
   điều kiện, bạn sẽ nhận được mã xác nhận", không tiết lộ email tồn tại hay không.
2. Gửi OTP riêng cho mục đích PASSWORD_RESET tới email đã xác minh của tài khoản
   đủ điều kiện. Dùng bộ thông số OTP đã duyệt như đăng ký/đổi mật khẩu: 6 chữ số,
   5 phút, gửi lại sau 60 giây, tối đa 5 email/giờ cho một địa chỉ; sai 5 lần chờ
   15 phút và gửi lại không đặt lại giới hạn thử sai.
3. OTP hợp lệ cho quyền đặt lại mật khẩu trong 5 phút, dùng một lần; không cấp
   phiên đăng nhập đầy đủ và không dùng quyền này cho thao tác khác.
4. Người dùng nhập mật khẩu mới và xác nhận; không yêu cầu mật khẩu cũ.
5. Khi hoàn tất mới cập nhật password hash, tăng auth_version, thu hồi phiên/token
   và kết thúc quyền đặt lại trong cùng transaction. Tất cả thiết bị phải đăng
   nhập lại; không tự đăng nhập sau reset.

OTP/quyền đặt lại mật khẩu không thay thế xác minh đăng ký và không tự mở một
tài khoản LOCKED/DISABLED. Chính sách khôi phục khi tài khoản đang bị khóa phải
được đối chiếu riêng với chính sách khóa/mở tài khoản. Thời gian chờ OTP không
được dùng để khóa toàn bộ tài khoản.

Tham khảo phản hồi thống nhất, token một lần có hạn và đăng nhập lại sau reset:
https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html

Chỉ ghi tài liệu ở lượt này; chưa sửa SQL/entity hoặc thực hiện gửi email/đặt lại
mật khẩu của tài khoản thực tế.

## D06 Đăng nhập và phiên đã được xác nhận

Người dùng xác nhận ngày 25/10/2026: "Có duyệt đi" đối với chính sách đăng nhập,
phiên độc lập trên nhiều thiết bị, thời hạn 7 ngày và access JWT 15 phút.

| Nội dung | Đã chốt |
|---|---|
| Đăng nhập thông thường | Email và mật khẩu; không yêu cầu OTP ở mỗi lần đăng nhập |
| Điều kiện sử dụng tài khoản | ACTIVE và email đã xác minh |
| Nhiều thiết bị | Cho phép nhiều phiên độc lập; một lần đăng nhập tạo một phiên, không coi user-agent là định danh xác thực thiết bị |
| Thời hạn phiên | Tối đa 7 ngày tính từ lúc đăng nhập; hoạt động/refresh không tự kéo dài giới hạn này |
| Quyền truy cập ngắn hạn | Access JWT 15 phút, nhưng không vượt hạn phiên; hệ thống tự refresh khi phiên còn hợp lệ |
| Refresh | Token ngẫu nhiên có rotation, lưu hash; hạn token không vượt hạn tối đa của phiên |
| Đăng xuất thông thường | Thu hồi phiên hiện tại; thiết bị khác vẫn giữ phiên riêng |
| Đổi/đặt lại mật khẩu | Thu hồi toàn bộ phiên/token và yêu cầu đăng nhập lại, theo D04/D05 đã chốt |

Hết 15 phút không đồng nghĩa người dùng phải nhập mật khẩu lại nếu phiên vẫn
hợp lệ. Hết 7 ngày hoặc phiên bị thu hồi thì cần đăng nhập lại. Không tự mở lại
tài khoản LOCKED/DISABLED trong refresh.

Màn hình xem/quản lý phiên và giới hạn số phiên đồng thời còn cần xác định phạm
vi. Chính sách chống dò mật khẩu và khóa/mở đã được duyệt riêng ở D08. Chưa sửa
entity, JWT config, schema hay thời hạn phiên thực tế của một tài khoản.

## D07 Cấp quyền và vai trò đã được xác nhận

Giữ quyết định đã có: một user có thể mang nhiều role. Ba actor theo ghi chú của
giảng viên tương ứng các vai trò dưới đây. Người dùng xác nhận ngày 25/10/2026:
"Có duyệt đi" đối với quy trình cấp quyền và tác động phiên.

| Vai trò | Phạm vi chức năng đã chốt |
|---|---|
| LEARNER | Học, luyện tập và truy cập tiến độ/kết quả của chính mình |
| CONTENT_ADMIN | Vai trò giáo viên/quản trị nội dung; quản lý học liệu, bài học, ngân hàng câu hỏi và nội dung luyện tập |
| SYSTEM_ADMIN | Quản lý tài khoản, cấp/thu hồi role, khóa/mở theo chính sách, cấu hình và nhật ký quản trị |

Quy trình cấp quyền đã được xác nhận:

- Đăng ký công khai luôn bắt đầu với LEARNER; không nhận lựa chọn role đặc quyền
  từ form đăng ký hoặc cập nhật profile.
- Giáo viên đăng ký và xác minh email như tài khoản bình thường; SYSTEM_ADMIN
  cấp thêm CONTENT_ADMIN cho tài khoản ACTIVE đã xác minh.
- SYSTEM_ADMIN đầu tiên được cấp riêng khi khởi tạo hệ thống, không có form đăng
  ký công khai cho vai trò này. Cấp thêm SYSTEM_ADMIN sau đó là thao tác của quản
  trị hệ thống đang hoạt động trên tài khoản đã đủ điều kiện.
- Chỉ SYSTEM_ADMIN được cấp/thu hồi role qua chức năng quản trị. Role độc lập,
  không tự kế thừa; muốn một người vừa học vừa quản trị thì gán các role tương ứng.
- Thay đổi role làm tăng auth_version và thu hồi toàn bộ phiên/token của user
  bị thay đổi; user đó đăng nhập lại để nhận đúng quyền mới.
- Không cho thao tác quản trị thông thường thu hồi/vô hiệu hóa quản trị hệ thống
  hoạt động cuối cùng, hoặc để user không còn role nào. Cần kiểm soát transaction
  để hai admin thao tác đồng thời không vượt qua các điều kiện này.
- Lưu audit người thực hiện, user đích, role trước/sau và thời điểm. Danh mục
  ban đầu có ba role cố định; chưa tự thêm tính năng tạo role/permission động.

Các quy trình cấp quyền, bảo vệ admin cuối cùng và tác động phiên ở mục D07 đã
được người dùng duyệt; chưa sửa service, giao diện, entity hoặc migration. Quyền
chi tiết từng thao tác biên soạn/duyệt/publish sẽ đối chiếu ở nhóm Content.

## D08 Khóa mở tài khoản và giới hạn đăng nhập đã được xác nhận

Người dùng xác nhận ngày 25/10/2026: "Ok duyệt" đối với nhóm quy tắc khóa/mở và
giới hạn thử đăng nhập. Các con số giới hạn là chính sách dự án đã được duyệt.

| Tình huống | Xử lý đã chốt |
|---|---|
| Nhập sai mật khẩu 5 lần trong 15 phút | Tạm dừng thử đăng nhập cho định danh đó 15 phút; không đổi trạng thái user, không thu hồi các phiên đang dùng hợp lệ |
| LOCKED | SYSTEM_ADMIN khóa tạm vì lý do bảo mật, có lý do và hạn khóa; mở lại khi hết hạn hoặc quản trị mở sớm |
| DISABLED | SYSTEM_ADMIN vô hiệu hóa, có lý do; chỉ mở lại bằng thao tác quản trị, không tự mở theo thời gian |

- Bộ đếm đăng nhập gắn với định danh email, bổ sung giới hạn theo IP; không chỉ
  dựa IP. Xử lý nhất quán cả email không tồn tại để không tạo tín hiệu dò tài khoản.
- Request bị giới hạn không tự kéo dài thời gian chờ; đăng nhập thành công thì
  đặt lại bộ đếm thất bại liên quan. Không dùng giới hạn này để chặn luồng quên
  mật khẩu đã chốt; luồng đó vẫn có giới hạn OTP riêng.
- Khi quản trị chuyển sang LOCKED/DISABLED, tăng auth_version và thu hồi mọi
  phiên/token của user. Mở lại không phục hồi các phiên đã thu hồi; cần đăng nhập.
- Chỉ mở sang ACTIVE khi email đã xác minh; chưa xác minh thì quay về
  PENDING_VERIFICATION. OTP không tự xóa khóa quản trị.
- Ghi người thực hiện, lý do, thời điểm khóa/mở và hạn khóa. Tác vụ mở khóa phải
  kiểm tra trạng thái hiện tại để không mở nhầm user đã bị chuyển sang DISABLED.
- Giữ điều kiện bảo vệ SYSTEM_ADMIN hoạt động cuối cùng theo D07; không xóa dữ
  liệu học tập hoặc kết quả khi khóa/vô hiệu hóa.

Tham khảo nguyên tắc giới hạn thử, observation window và cân bằng lockout/khả dụng:
https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html#login-throttling

Đã duyệt ở mức nghiệp vụ; chưa sửa dữ liệu, enum, counter hay cơ chế khóa thực tế.

## D09 Dữ liệu bảng users đã được xác nhận

Người dùng xác nhận ngày 25/10/2026: "Duyệt đi" đối với danh sách dữ liệu của
users như bảng dưới. Đây là danh sách dữ liệu và mục đích; chưa phải SQL đã
chốt toàn bộ nullability, index hoặc constraint.

| Trường | Ý nghĩa |
|---|---|
| id | UUID định danh tài khoản, ổn định trong suốt vòng đời |
| email | Email đăng nhập duy nhất, giữ cố định trong bản đầu |
| password_hash | Giá trị hash của mật khẩu, không lưu mật khẩu rõ |
| first_name | Phần tên, có thể gồm tên đệm; tối đa 30 ký tự theo điều chỉnh ở D20 |
| last_name | Phần họ; tối đa 30 ký tự theo điều chỉnh ở D20 |
| phone | Số điện thoại bổ sung tại profile, tùy chọn |
| avatar_media_id | Tham chiếu metadata ảnh đại diện, tùy chọn |
| status | Một trong 4 trạng thái đã duyệt |
| email_verified_at | Thời điểm xác minh email; cần giữ ngay cả khi tài khoản bị khóa |
| locked_until | Hạn khóa tạm khi status là LOCKED |
| password_changed_at | Thời điểm thay đổi mật khẩu gần nhất |
| auth_version | Phiên bản xác thực, tăng khi đổi mật khẩu/quyền/khóa để vô hiệu quyền cũ |
| version | Phiên bản dữ liệu để chống cập nhật ghi đè khi các request cùng sửa tài khoản |
| created_at | Thời điểm tạo tài khoản |
| updated_at | Thời điểm cập nhật tài khoản gần nhất |

Role được liên kết qua roles/user_roles; lịch sử và người/lý do khóa/mở nằm ở dữ
liệu audit. Yêu cầu OTP, đăng ký dở, phiên và token có vòng đời riêng, cần được
duyệt quan hệ và dữ liệu ở các bảng liên quan, không gom vào users.

Điều kiện ACTIVE/email_verified_at, hạn LOCKED, profile tùy chọn và cách giữ thông
tin đăng ký chưa xác minh sẽ được kiểm tra đồng bộ khi duyệt các bảng xác minh.
Danh sách D09 đã được người dùng duyệt. Ngày 25/10/2026, người dùng yêu cầu thay
display_name bằng first_name/last_name, mỗi trường tối đa 30 ký tự theo D20;
bảng trên đã phản ánh điều chỉnh này. Chưa sửa entity hoặc migration.

## D10 Dữ liệu roles và user_roles đã được xác nhận

Người dùng xác nhận ngày 25/10/2026: "OK duyệt" đối với dữ liệu và quan hệ của
roles/user_roles dưới đây. Chưa thực hiện thay đổi schema.

### roles

| Trường | Ý nghĩa đã chốt |
|---|---|
| id | SMALLINT, định danh catalogue role |
| code | Mã duy nhất: LEARNER, CONTENT_ADMIN hoặc SYSTEM_ADMIN |
| name | Tên hiển thị của vai trò |

Đây là catalogue ba role cố định của bản đầu, do dữ liệu khởi tạo quản lý.
Code dùng cho kiểm tra quyền; tên hiển thị không được dùng thay code.

### user_roles

| Trường | Ý nghĩa đã chốt |
|---|---|
| user_id | FK users, người được cấp quyền |
| role_id | FK roles, vai trò được cấp |
| assigned_at | Thời điểm cấp role hiện hành |
| assigned_by | FK users, quản trị viên thực hiện cấp quyền; có thể NULL khi hệ thống tự gán role đăng ký hoặc khởi tạo |

- Khóa chính ghép (user_id, role_id); một user không nhận trùng cùng role.
- Không cần thêm UUID riêng cho mỗi liên kết role.
- Một user có thể có nhiều liên kết; một role có thể được cấp cho nhiều user.
- Bảng nối giữ các role hiện hành. Khi thu hồi thì xóa liên kết tương ứng và
  giữ lịch sử cấp/thu hồi trong audit; không xóa user hoặc role catalogue.
- Giữ ràng buộc nghiệp vụ user còn ít nhất một role và bảo vệ SYSTEM_ADMIN
  hoạt động cuối cùng theo D07. PK/FK không tự bảo đảm hai điều kiện tổng thể này;
  service cần kiểm tra an toàn trong transaction.
- Thu hồi/cấp quyền phải đi cùng auth_version, session revocation và audit đã chốt.

Danh sách trường và ràng buộc của D10 đã được người dùng duyệt; chưa thêm
assigned_by vào entity hoặc migration hiện có.

## D11 Dữ liệu auth_sessions đã được xác nhận

Người dùng xác nhận ngày 25/10/2026: "Ok thế chốt đi" sau khi làm rõ metadata
phiên. Mỗi lần đăng nhập tạo một phiên độc lập theo D06. Bảng auth_sessions giữ
trạng thái phiên và thông tin tham khảo của lần đăng nhập như sau.

| Trường | Ý nghĩa đã chốt |
|---|---|
| id | UUID định danh phiên; JWT/refresh liên kết đúng phiên này |
| user_id | FK users, tài khoản sở hữu phiên |
| user_agent | Thông tin trình duyệt/ứng dụng gửi request, có thể để trống |
| ip_address | IP khi đăng nhập, có thể để trống nếu không xác định được tin cậy |
| created_at | Thời điểm tạo phiên/đăng nhập |
| last_active_at | Thời điểm ghi nhận hoạt động gần nhất, có thể để trống |
| expires_at | Hạn tối đa, 7 ngày từ created_at theo chính sách đã chốt |
| revoked_at | Thời điểm thu hồi phiên, NULL khi chưa bị thu hồi |
| revoke_reason | Lý do thu hồi, ví dụ logout, đổi/reset password, đổi role hoặc khóa tài khoản |

- Một user có thể có nhiều phiên; không dùng user-agent/IP làm khóa xác thực hoặc
  khẳng định đó là định danh duy nhất của một thiết bị vật lý.
- user_agent và ip_address là metadata tùy chọn phục vụ nhận biết phiên/kiểm tra
  sự cố. Nhãn device_label được suy ra từ user_agent khi hiển thị, không có cột
  lưu riêng trong bản này. Chức năng cho người dùng tự đặt tên thiết bị chưa thuộc
  phạm vi đã duyệt.
- last_active_at không làm kéo dài expires_at. Tần suất ghi nhận có thể được
  điều tiết khi triển khai; không dùng cột này suy ra thời lượng học thực tế.
- Một phiên chỉ hợp lệ khi chưa thu hồi, chưa hết hạn và user còn đủ điều kiện
  hoạt động. NULL revoked_at không đồng nghĩa phiên chắc chắn còn hiệu lực.
- revoked_at và revoke_reason cùng có khi thu hồi; mở tài khoản không phục hồi
  phiên cũ đã bị thu hồi. Refresh token sẽ được duyệt trong bảng riêng.
- IP lấy từ kết nối/chuỗi proxy được tin cậy khi triển khai, không tin giá trị
  tùy ý trong payload hoặc header proxy từ nguồn không được cấu hình.
- Vòng đời và thời gian giữ metadata phiên đã hết hạn/thu hồi cần chốt trong
  chính sách retention; không tự dọn dữ liệu ở bước này.

Danh sách D11 và điều chỉnh metadata đã được người dùng duyệt; chỉ sửa tài liệu,
chưa sửa bảng
auth_sessions trong migration hoặc tạo entity mới.

## D12 Dữ liệu refresh_tokens đã được xác nhận

auth_sessions lưu phiên đăng nhập, còn refresh_tokens lưu các lần cấp token gia
hạn thuộc phiên đó. Người dùng xác nhận ngày 25/10/2026: "Có duyệt đi" đối với
danh sách dữ liệu và vòng đời rotation bên dưới; chưa sửa SQL.

| Trường | Ý nghĩa đã chốt |
|---|---|
| id | UUID định danh bản ghi refresh token |
| session_id | FK auth_sessions, phiên sở hữu token |
| token_hash | Hash SHA-256 của token ngẫu nhiên, duy nhất; không lưu raw token |
| issued_at | Thời điểm cấp token |
| expires_at | Thời hạn token, không vượt expires_at của phiên |
| revoked_at | Thời điểm thu hồi, NULL khi chưa thu hồi |
| replaced_by_id | Tham chiếu token mới thay thế token này; phải thuộc cùng phiên |
| last_used_at | Thời điểm token được sử dụng hợp lệ để refresh |

- Một phiên có nhiều bản ghi token theo lịch sử rotation, nhưng tối đa một token
  chưa thu hồi tại một thời điểm. Token hết hạn vẫn phải bị từ chối dù revoked_at
  còn NULL; cần đóng token cũ trước khi cấp token mới trong transaction.
- Refresh hợp lệ tạo token mới và thu hồi token vừa dùng. Mối liên kết replacement
  giữ chuỗi lịch sử, không cho trỏ sang session khác hoặc trỏ lại chính token đó.
- Rotation không kéo dài hạn tối đa 7 ngày của phiên theo D06.
- Raw token được cấp cho client bằng cookie bảo mật theo kiến trúc đã có; DB giữ
  hash để tra cứu/xác minh. Access JWT ngắn hạn không lưu vào bảng này.
- Request refresh đồng thời hoặc retry sau lỗi mạng cần xử lý có kiểm soát; token
  cũ không được tạo ra hai token mới độc lập. Cơ chế phân biệt retry/race với reuse
  trái phép sẽ được xác định và kiểm thử khi hoàn thiện transaction/auth.
- Không xóa ngay lịch sử token vừa rotate; thời gian giữ sau hết hạn/thu hồi cần
  chốt cùng retention phiên và các dữ liệu bảo mật liên quan.

Danh sách D12 đã được người dùng duyệt; chưa sửa SQL, entity, cookie hoặc cơ chế
refresh đang chạy.

## D13 Dữ liệu OTP đã được xác nhận

Người dùng xác nhận ngày 25/10/2026: "Ok duyệt" đối với mô hình và dữ liệu OTP.
Bảng otp_challenges dùng chung cấu trúc xác minh mã cho ba mục đích. Mỗi
yêu cầu luôn gắn đúng user/mục đích; dữ liệu nghiệp vụ như thông tin đăng ký chờ
xác minh, mật khẩu mới hoặc quyền reset sẽ có liên kết và vòng đời riêng.

| Trường | Ý nghĩa đã chốt |
|---|---|
| id | UUID định danh yêu cầu xác minh |
| user_id | FK users, chủ tài khoản cần xác minh, gồm user chờ kích hoạt khi đăng ký |
| purpose | EMAIL_VERIFICATION, PASSWORD_CHANGE hoặc PASSWORD_RESET |
| otp_hmac | Giá trị xác thực mã có secret server; không lưu OTP rõ |
| created_at | Thời điểm tạo yêu cầu |
| expires_at | Hạn hiệu lực mã hiện tại, 5 phút theo chính sách đã chốt |
| last_sent_at | Thời điểm gửi mã gần nhất |
| attempt_count | Số lần nhập sai được ghi nhận cho yêu cầu |
| blocked_until | Hạn tạm dừng xác minh khi vượt số lần thử |
| consumed_at | Thời điểm OTP được xác minh và dùng thành công, không dùng lại được |
| invalidated_at | Thời điểm yêu cầu/mã bị hủy hoặc thay thế |

- Cùng cấu trúc lưu trữ không có nghĩa dùng chung mã giữa ba mục đích. Mỗi API
  phải kiểm tra user, purpose, hiệu lực và ngữ cảnh thao tác tương ứng.
- OTP hợp lệ phải chưa consumed/invalidated/hết hạn và không bị tạm dừng. Chấp
  nhận mã, cập nhật trạng thái và thực hiện bước tiếp theo cần transaction.
- Đăng ký: xác minh thành công gắn với đúng thông tin của lượt đăng ký đã chứng
  minh quyền sở hữu email. Đổi mật khẩu: gắn với phiên/ngữ cảnh và mật khẩu mới
  đã yêu cầu. Không nhét password rõ hoặc payload tùy ý vào otp_challenges.
- Quên mật khẩu: OTP thành công cho quyền reset giới hạn 5 phút; dùng quyền reset
  và hoàn tất đổi password là bước riêng, không coi consumed_at là đã đổi password.
- Resend chỉ mã mới còn hiệu lực; không làm mất bộ đếm sai hoặc thời gian chờ.
  Cách liên kết yêu cầu nghiệp vụ, giữ bộ đếm ổn định qua resend/thay mã và giới hạn
  gửi chung theo email phải được chốt đồng bộ trước khi viết schema.
- Hạn mã 5 phút khác hạn đăng ký dở 7 ngày. Không dùng expires_at của OTP để xóa
  một tài khoản hoặc suy ra một phiên đăng nhập đã hết hạn.

D13 đã được người dùng duyệt ở mức mô hình và danh sách dữ liệu. Chưa thêm bảng
otp_challenges vào SQL hoặc thay các bảng/yêu cầu xác minh hiện có.

## D14 Yêu cầu đăng ký đã được xác nhận

Người dùng xác nhận ngày 25/10/2026: "Ok chốt đi" đối với bảng và vòng đời yêu
cầu đăng ký. registration_requests lưu dữ liệu chờ xác minh của một lượt đăng ký,
liên kết với user PENDING_VERIFICATION và OTP của chính lượt đó. Mục tiêu là
không kích hoạt tài khoản bằng mật khẩu của một lượt đăng ký khác cùng email.

| Trường | Ý nghĩa đã chốt |
|---|---|
| id | UUID định danh lượt đăng ký |
| user_id | FK users, tài khoản đang chờ xác minh |
| otp_challenge_id | FK otp_challenges, yêu cầu EMAIL_VERIFICATION đúng user/lượt đăng ký |
| pending_first_name | Phần tên đang chờ người đăng ký xác nhận, tối đa 30 ký tự |
| pending_last_name | Phần họ đang chờ người đăng ký xác nhận, tối đa 30 ký tự |
| pending_password_hash | Hash mật khẩu của chính lượt đăng ký này |
| created_at | Thời điểm tạo lượt đăng ký |
| expires_at | Hạn đăng ký dở, theo thời hạn 7 ngày đã chốt |
| completed_at | Thời điểm hoàn tất xác minh và ghi nhận thông tin hợp lệ |
| invalidated_at | Thời điểm lượt đăng ký bị hủy hoặc thay thế |

- Một user có thể có lịch sử nhiều lượt đăng ký dở, nhưng chỉ có tối đa một lượt
  đang mở tại một thời điểm. Khi thay lượt cũ phải vô hiệu hóa đúng lượt/OTP cũ.
- Đăng ký lại hoặc resend trong cùng đợt chưa hết hạn không kéo dài hạn 7 ngày
  ban đầu. Hết đợt mới có thể thực hiện luồng đăng ký lại theo chính sách đã duyệt.
- OTP hết hạn 5 phút không đồng nghĩa registration_requests hết hạn; có thể xin
  mã mới trong thời hạn đăng ký, vẫn tuân thủ giới hạn gửi/thử đã chốt.
- Chỉ dữ liệu của lượt hiện hành đã xác minh OTP mới được chuyển thành họ/tên và
  credential chính thức trong users. Như vậy password_hash/first_name/last_name của user
  chờ xác minh cần có quy tắc nullability theo trạng thái khi chốt schema; danh
  sách D09 chưa ép NOT NULL cho mọi trạng thái.
- Hoàn tất OTP, ghi email_verified_at, cập nhật thông tin user và completed_at
  phải atomic. Chuyển ACTIVE chỉ khi tài khoản đủ điều kiện; không tự mở user
  đang LOCKED/DISABLED.
- Sau khi chuyển thông tin sang users, dọn pending_password_hash của yêu cầu đã
  hoàn tất để không giữ thêm bản sao credential. Metadata thời gian/trạng thái
  được giữ theo retention đã duyệt ở D19.
- Việc thay yêu cầu không được xóa giới hạn thử sai/thời gian chờ hoặc giới hạn
  gửi theo email. Cơ chế giữ các bộ đếm chung phải được duyệt cùng nhóm giới hạn
  yêu cầu trước khi hoàn thiện SQL; không chỉ dựa counter của một bản ghi mới.

D14 đã được người dùng duyệt ở mức bảng và vòng đời dữ liệu. Điều chỉnh họ/tên
ở D20 thay pending_display_name bằng pending_first_name/pending_last_name để
khớp users. Chưa tạo registration_requests hoặc sửa users/OTP trong SQL/entity.

## D15 Yêu cầu đổi mật khẩu đã được xác nhận

Người dùng xác nhận ngày 25/10/2026: "Ok chốt đi" đối với dữ liệu và thời hạn
yêu cầu đổi mật khẩu. password_change_requests lưu thao tác đã kiểm tra mật khẩu
hiện tại nhưng đang chờ OTP theo D04. Cần giữ đúng mật khẩu mới, user và phiên
đã khởi tạo thao tác; không chỉ kiểm tra một OTP chung rồi nhận password tùy ý.

| Trường | Ý nghĩa đã chốt |
|---|---|
| id | UUID định danh yêu cầu đổi mật khẩu |
| user_id | FK users, tài khoản muốn đổi mật khẩu |
| session_id | FK auth_sessions, phiên đăng nhập đã khởi tạo yêu cầu |
| otp_challenge_id | FK otp_challenges, mã PASSWORD_CHANGE đúng user/yêu cầu |
| pending_password_hash | Hash mật khẩu mới đang chờ xác nhận |
| auth_version_at_request | Phiên bản xác thực của user lúc kiểm tra mật khẩu hiện tại |
| created_at | Thời điểm tạo yêu cầu sau khi kiểm tra mật khẩu hiện tại |
| expires_at | Tối đa 5 phút từ lúc tạo yêu cầu, không vượt hạn phiên |
| completed_at | Thời điểm đổi mật khẩu thành công |
| invalidated_at | Thời điểm yêu cầu bị hủy hoặc thay thế |

- Một user chỉ có tối đa một yêu cầu đổi mật khẩu đang mở. Tạo yêu cầu
  mới sau khi kiểm tra mật khẩu hiện tại sẽ vô hiệu hóa yêu cầu/OTP cũ phù hợp.
- Chỉ phiên đã tạo yêu cầu được hoàn tất; user phải còn ACTIVE/đã xác minh và
  phiên phải còn hợp lệ. auth_version hiện tại phải khớp auth_version_at_request
  để một yêu cầu cũ không được dùng sau lần đổi credential/quyền/khóa khác.
- Chỉ chấp nhận khi cả yêu cầu lẫn OTP còn hiệu lực. Resend không kéo dài hạn
  xác thực mật khẩu hiện tại của yêu cầu; hạn thực tế hiển thị cho người dùng
  phải tôn trọng giới hạn sớm hơn giữa OTP và yêu cầu. Hết hạn cần bắt đầu lại,
  nhập mật khẩu hiện tại; không tự tái dùng bằng chứng xác thực cũ.
- Khi OTP hợp lệ, đổi hash, ghi password_changed_at, tăng auth_version, hoàn tất
  yêu cầu/OTP và thu hồi mọi phiên/token trong cùng transaction theo D04.
- Xóa pending_password_hash sau hoàn tất/hủy theo retention để không giữ thêm
  bản sao credential; metadata trạng thái/thời gian có vòng đời riêng.
- Thay yêu cầu hoặc resend không xóa giới hạn gửi/thử sai đã chốt. Mật khẩu hiện
  tại và mật khẩu mới dạng rõ không lưu trong bảng này.

Các trường, giới hạn một yêu cầu mở và hạn yêu cầu 5 phút của D15 đã được duyệt.
Chỉ bổ sung mô hình dữ liệu, chưa tạo bảng hoặc thay đổi thao tác mật khẩu thực tế.

## D16 Quyền đặt lại mật khẩu đã được xác nhận

Người dùng xác nhận ngày 25/10/2026: "Ok chốt đi" đối với dữ liệu và vòng đời
quyền đặt lại mật khẩu. password_reset_grants lưu quyền sau khi xác minh OTP
PASSWORD_RESET theo D05. Đây là quyền tạm cho một thao tác, không phải role trong
user_roles hay phiên đăng nhập trong auth_sessions.

| Trường | Ý nghĩa đã chốt |
|---|---|
| id | UUID định danh quyền đặt lại |
| user_id | FK users, tài khoản được đặt lại mật khẩu |
| otp_challenge_id | FK otp_challenges, bằng chứng PASSWORD_RESET đã được xác minh đúng user |
| grant_hash | Hash của mã quyền tạm ngẫu nhiên, duy nhất; không lưu mã quyền rõ |
| auth_version_at_issue | Phiên bản xác thực của user khi cấp quyền |
| issued_at | Thời điểm cấp quyền sau khi xác minh OTP |
| expires_at | Hạn quyền, 5 phút từ issued_at theo D05 |
| used_at | Thời điểm hoàn tất đặt lại mật khẩu bằng quyền này |
| invalidated_at | Thời điểm quyền bị hủy hoặc thay thế |

- Một OTP PASSWORD_RESET chỉ cấp một grant; mỗi user chỉ có tối đa một
  grant chưa dùng/chưa hủy tại một thời điểm. Cấp grant mới sau khi xác minh hợp lệ
  thì hủy grant cũ, không hủy grant đang có chỉ vì ai đó yêu cầu gửi OTP mới.
- Xác minh OTP, consume mã và cấp grant phải atomic. OTP không được dùng lại để
  phát sinh thêm grant. API xác minh không cấp access/refresh token đăng nhập.
- Grant chỉ được dùng cho reset password, đúng user, còn hạn, chưa dùng/hủy và
  khớp auth_version hiện tại. Quyền cũ không còn hợp lệ sau khi mật khẩu/quyền/
  trạng thái bảo mật đã thay đổi; không tự mở tài khoản LOCKED/DISABLED.
- Khi đặt lại thành công, cập nhật password hash, password_changed_at và
  auth_version, đánh dấu used_at, hủy quyền reset còn lại và thu hồi phiên/token
  trong cùng transaction; sau đó người dùng đăng nhập lại như D05.
- Mật khẩu mới được nhập sau bước OTP, kiểm tra rồi ghi vào users khi hoàn tất;
  không cần cột lưu mật khẩu mới trong password_reset_grants.
- Mã quyền tạm phải được giữ bí mật, không đưa vào URL/log hoặc lưu lâu dài ở
  trình duyệt. Cách chuyển mã giữa FE/BE sẽ được thiết kế ở hợp đồng API.

D16 đã được duyệt ở mức bảng, dữ liệu và vòng đời quyền tạm. Chỉ cập nhật tài
liệu; chưa tạo SQL, cấp grant thật hoặc đặt lại mật khẩu của tài khoản thực tế.

## D17 Dữ liệu giới hạn gửi và thử xác thực đã được xác nhận

Người dùng xác nhận ngày 25/10/2026: "Ok duyệt" đối với hai bảng và cách cộng
chung hạn mức gửi OTP theo email. D03/D08/D13 yêu cầu giữ giới hạn qua resend
hoặc thay yêu cầu. Dữ liệu được giữ
chung theo địa chỉ email, độc lập với user ID và challenge ID, để cả email chưa
có tài khoản cũng chịu giới hạn nhất quán. Đây là cơ chế thực hiện chính sách
đã chốt, không phải lịch sử đăng nhập đầy đủ hay bảng audit nghiệp vụ.

Đã chốt hai bảng liên quan, trước mắt dùng cùng PostgreSQL của dự án:

### auth_rate_limits: trạng thái của một giới hạn

| Trường | Ý nghĩa đã chốt |
|---|---|
| id | UUID định danh bộ giới hạn |
| scope | Quy tắc đang áp dụng: gửi OTP, thử OTP theo từng mục đích, hoặc đăng nhập sai |
| subject_hash | HMAC của email đã chuẩn hóa theo chính sách đăng nhập; phạm vi IP nếu bổ sung có khóa riêng |
| blocked_until | Thời điểm hết tạm dừng, có thể trống |
| reset_at | Mốc bắt đầu tính lại lỗi xác thực sau thành công hợp lệ hoặc kết thúc thời gian chờ; không đặt lại hạn mức gửi email |
| updated_at | Thời điểm cập nhật trạng thái |

- Unique(scope, subject_hash). Bộ giới hạn gửi email dùng chung cho cả ba mục
  đích OTP; bộ giới hạn thử mã tách theo EMAIL_VERIFICATION, PASSWORD_CHANGE,
  PASSWORD_RESET. Đăng nhập sai có bộ giới hạn riêng.
- Không FK user_id vì email có thể chưa tồn tại trong users. Không dùng mã OTP,
  mật khẩu, refresh token hoặc nội dung thư làm khóa/bản ghi giới hạn.

### auth_rate_limit_events: những lần được tính vào giới hạn

| Trường | Ý nghĩa đã chốt |
|---|---|
| id | Số định danh tự tăng |
| rate_limit_id | FK auth_rate_limits, quy tắc và địa chỉ được tính |
| occurred_at | Thời điểm lần gửi được chấp nhận hoặc lần xác thực sai được tính |

- Tối đa 5 lần gửi OTP trong 60 phút gần nhất cho mỗi email, cách nhau ít nhất
  60 giây, cộng gộp cả ba mục đích. Ghi nhận khi hệ thống chấp nhận lượt gửi;
  retry kỹ thuật của cùng lượt gửi không tính thành yêu cầu gửi mới. Đăng nhập/
  xác minh thành công không xóa hạn mức gửi này.
- Đăng nhập sai 5 lần trong 15 phút gần nhất thì chờ 15 phút theo D08. Thành
  công hợp lệ đặt lại phần đếm lỗi tương ứng. Nhập sai OTP 5 lần thì chờ 15 phút
  theo D03; resend hoặc thay challenge không xóa số lỗi/thời gian chờ này.
- Sau khi hết thời gian chờ, bắt đầu lượt đếm lỗi mới. Yêu cầu bị từ chối trong
  lúc chờ không gia hạn thời gian chờ. Mỗi scope độc lập; xác minh một mục đích
  không xóa lỗi của mục đích khác.
- Lưu mốc từng lần để tính cửa sổ thời gian trượt, tránh xóa hạn mức chỉ vì sang
  giờ mới. Kiểm tra giới hạn, ghi nhận lần được tính và cập nhật chờ cần được
  bảo vệ bằng transaction/khóa bản ghi khi có request đồng thời.
- Bộ đếm chung là nguồn quyết định giới hạn qua nhiều challenge. attempt_count
  trong D13 mô tả lần thử trên challenge cụ thể; blocked_until của challenge
  không được làm nới lỏng giới hạn chung hoặc tự khởi động lại thời gian chờ.
- Dọn sự kiện khi đã hết thời gian cần cho cửa sổ đếm; chỉ xóa trạng thái khi
  không còn thời gian chờ hoặc dữ liệu đếm cần bảo toàn. Thông số chống lạm dụng
  theo IP và lịch dọn vật lý sẽ được chốt khi thiết kế triển khai.

D17 đã được duyệt, gồm mô hình hai bảng và phạm vi cộng gộp hạn mức gửi theo
email. Chỉ cập nhật tài liệu; chưa tạo bảng hoặc thay đổi giới hạn của hệ thống thật.

## D18 Nhật ký thao tác quản trị tài khoản đã được xác nhận

Người dùng xác nhận ngày 25/10/2026: "Ok chốt" đối với cấu trúc audit_logs và
quy tắc ghi nhật ký quản trị tài khoản. D07/D08 đã yêu cầu ghi lịch sử cấp/thu hồi
role và khóa/mở tài khoản. Dùng chung audit_logs đã có trong bản thiết kế cũ,
làm rõ dữ liệu cho nhóm Identity trước; không thêm một bảng audit riêng cho từng
thao tác.

| Trường | Ý nghĩa đã chốt |
|---|---|
| id | UUID định danh bản ghi |
| actor_type | USER hoặc SYSTEM, phân biệt người thực hiện với tác vụ tự động |
| actor_user_id | FK users; bắt buộc khi actor_type=USER, trống khi SYSTEM |
| action | Mã thao tác do ứng dụng quy định, ví dụ USER_ROLE_GRANTED, USER_LOCKED |
| entity_type | Loại đối tượng chịu tác động; nhóm đang duyệt dùng USER |
| entity_id | UUID đối tượng chịu tác động, ở đây là user bị đổi quyền/trạng thái |
| metadata | JSONB theo cấu trúc từng action, giữ role/trạng thái/hạn khóa trước và sau |
| reason | Lý do; bắt buộc với khóa, vô hiệu hóa hoặc mở lại bằng thao tác quản trị |
| request_id | Mã đối chiếu với yêu cầu xử lý, có thể trống với tác vụ nền |
| ip_address | IP nguồn nếu xác định được theo chuỗi proxy tin cậy; có thể trống với tác vụ nền |
| created_at | Thời điểm sự kiện được ghi nhận bởi hệ thống |

- Phạm vi hiện tại: cấp/thu hồi role, khóa tạm, mở khóa sớm, tự mở khi hết hạn,
  vô hiệu hóa, kích hoạt lại và cấp SYSTEM_ADMIN đầu tiên. Thao tác thủ công có
  actor là user thực hiện; tác vụ nền/bootstrap có actor_type=SYSTEM. Việc này
  không cấp thêm quyền mới cho SYSTEM_ADMIN ngoài D07/D08.
- Mỗi thay đổi thành công ghi một sự kiện cùng transaction với thay đổi quyền/
  trạng thái và thu hồi phiên theo chính sách đã duyệt. Không để cập nhật user
  thành công nhưng thiếu lịch sử. Request thất bại/no-op không được ghi như một
  thay đổi thành công; theo dõi thử truy cập thất bại là phạm vi log kỹ thuật riêng.
- metadata chỉ chứa danh sách trường được cho phép theo action: ví dụ role
  trước/sau hoặc status/locked_until trước/sau. Không sao chép toàn bộ entity,
  request body, mật khẩu/password hash, OTP/OTP HMAC, cookie hoặc token vào audit.
- entity_type/entity_id là tham chiếu dùng chung cho nhiều loại đối tượng, không
  phải FK đa hình do PostgreSQL tự kiểm tra. Với sự kiện USER, service phải kiểm
  tra user đích và ghi trong cùng transaction; actor_user_id có FK riêng. Khi
  duyệt Content/Administration sẽ đối chiếu cách dùng audit cho các đối tượng khác.
- Chỉ SYSTEM_ADMIN được xem nhật ký qua chức năng quản trị. Ứng dụng chỉ
  thêm bản ghi, không cung cấp chức năng sửa/xóa từng sự kiện. Không xóa dây
  chuyền audit khi thay đổi/xóa đối tượng; retention và điều kiện dọn được duyệt
  riêng. Không coi thiết kế này là bảo đảm chống sửa dữ liệu bởi chủ database.
- Trường actor, target, thời điểm và nội dung thay đổi do backend xác định từ
  thao tác đã được cho phép, không tin dữ liệu actor/metadata tùy ý từ frontend.

Tham khảo nguyên tắc chọn thuộc tính sự kiện và loại bỏ dữ liệu xác thực nhạy cảm:
https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html#data-to-exclude

D18 đã được duyệt ở mức dữ liệu và phạm vi nhật ký quản trị tài khoản. Chỉ cập
nhật tài liệu; chưa sửa bảng audit_logs trong SQL đề xuất, entity, phân quyền
hoặc cơ chế ghi log thật.

## D19 Vòng đời giữ và dọn dữ liệu tài khoản đã được xác nhận

Người dùng xác nhận ngày 25/10/2026: "Ok duyệt" đối với chính sách giữ/dọn dữ
liệu tài khoản, gồm mốc 30 ngày, 365 ngày và lịch dọn hằng ngày. Các D03/D11–D18
đã xác định lúc dữ liệu hết hiệu lực; D19 bổ sung lúc dọn vật lý cho nhóm Identity.
Chính sách giữ nội dung, kết quả học tập và media sẽ được duyệt trong các nhóm
tương ứng.

| Nhóm dữ liệu | Chính sách đã chốt |
|---|---|
| users đã từng hoạt động | Không tự xóa trong bản đầu; khóa/vô hiệu hóa vẫn giữ tài khoản, quyền và lịch sử học tập theo D08 |
| Đăng ký người học chưa từng xác minh/kích hoạt | Giữ 7 ngày từ đầu chu kỳ theo D03; được dọn cả hồ sơ đăng ký dở và dữ liệu xác thực phụ thuộc khi đủ điều kiện, không kéo dài hạn do resend |
| auth_sessions và refresh_tokens | Giữ metadata 30 ngày sau khi phiên hết hiệu lực do hết hạn/thu hồi; chỉ dọn cả chuỗi khi không còn yêu cầu đang hợp lệ cần tham chiếu |
| registration_requests, otp_challenges, password_change_requests, password_reset_grants đã kết thúc | Giữ metadata 30 ngày sau khi yêu cầu kết thúc; trường hợp đăng ký dở bị dọn ở ngày thứ 7 áp dụng ngoại lệ theo D03 |
| Giá trị xác thực tạm của yêu cầu đã kết thúc | Xóa pending_password_hash, otp_hmac và grant_hash khi đã dùng/hủy hoặc khi tác vụ dọn phát hiện hết hạn; không giữ các giá trị này cùng metadata trong 30 ngày |
| auth_rate_limits và auth_rate_limit_events | Chỉ dọn khi dữ liệu không còn cần cho việc đếm hoặc thời gian chờ theo D17; không dùng một TTL cố định làm mất số lỗi chưa được đặt lại |
| audit_logs | Giữ 365 ngày từ created_at; chỉ tác vụ dọn theo chính sách được xóa bản ghi đã quá hạn, không cung cấp thao tác xóa từng bản ghi qua giao diện quản trị |

- Hết hạn và bị từ chối xác thực là điều kiện được kiểm tra trực tiếp, không
  phụ thuộc việc tác vụ dọn đã chạy hay chưa. Tác vụ dọn dữ liệu hết hạn
  chạy hằng ngày; dữ liệu tạm mới hết hạn có thể còn chờ lần dọn kế tiếp nhưng
  không được sử dụng lại.
- Mốc giữ metadata tính từ lúc hoàn tất, hủy hoặc hết hạn thực tế. Khi các bảng
  có FK phụ thuộc nhau, dọn theo nhánh/thứ tự phụ thuộc và chỉ khi toàn bộ dữ liệu
  liên quan đã đủ điều kiện; không xóa cha làm mất yêu cầu/phiên còn hợp lệ.
- Ngoại lệ đăng ký dở 7 ngày chỉ áp dụng đúng điều kiện D03: chưa từng xác minh/
  hoạt động, không bị khóa/vô hiệu hóa hoặc là tài khoản admin được cấp, không
  có dữ liệu nghiệp vụ cần bảo toàn. Kiểm tra lại trong transaction trước khi
  dọn để không xóa tài khoản vừa xác minh hoặc thay đổi trạng thái.
- Bản đầu chưa bổ sung chức năng tự xóa tài khoản đã hoạt động. Việc xóa/anonymize
  tài khoản theo yêu cầu và tác động tới nội dung tác giả, kết quả, audit, backup
  cần duyệt thành luồng riêng trước khi triển khai.
- Giữ audit không có nghĩa là giữ credential hoặc token. Việc dọn không tạo
  audit chứa bí mật; các giá trị mật khẩu/OTP/token không được sao chép vào log.
- Các mốc 30 ngày, 365 ngày và lịch dọn hằng ngày là lựa chọn vận hành đã được
  người dùng duyệt cho dự án. Việc hết hạn
  5 phút/7 ngày của OTP, grant, yêu cầu hoặc phiên theo các D trước không đổi.
- Phạm vi này là database đang vận hành; vòng đời bản sao lưu và chính sách
  phục hồi cần được thiết kế trước khi triển khai vận hành, không mặc nhiên bị
  rút ngắn cùng thao tác dọn bảng.

D19 đã được duyệt. Chỉ cập nhật tài liệu; chưa sửa nullability của các trường
bí mật trong schema, tạo tác vụ dọn hoặc thực hiện xóa dữ liệu thực tế.

## D20 Ràng buộc email, mật khẩu và hồ sơ đã được xác nhận

Người dùng xác nhận ngày 25/10/2026: "Ok chốt" đối với D20 sau điều chỉnh họ/tên
và BCrypt. Các ràng buộc áp dụng cho users và các yêu cầu đăng ký/đổi mật khẩu
liên quan. Trước đó, người dùng yêu cầu: "Tên hiển thị nên
chia làm first name last name và chỉ giới hạn mỗi tên 30 ký tự th, hash mật khẩu
dùng bcryt". Đã ghi nhận first_name/last_name, mỗi trường tối đa 30 ký tự, và
BCrypt là các lựa chọn đã chốt; không dùng display_name như một cột riêng.

D20 không thêm bảng mới. Việc tách tên được đồng bộ tại D03/D09/D14 và phần hồ
sơ bên dưới; thuật toán áp dụng cho cả password_hash lẫn pending_password_hash.
Giới hạn đầu vào mật khẩu đã được điều chỉnh cho phù hợp BCrypt. Lượt xác nhận
sau điều chỉnh chốt cả các quy tắc còn lại của D20, gồm tối thiểu 15 ký tự và
tối đa 72 byte UTF-8 cho mật khẩu.

| Dữ liệu | Ràng buộc đã chốt |
|---|---|
| email | Bắt buộc, đúng định dạng địa chỉ email, tối đa 254 ký tự sau trim; so sánh không phân biệt hoa/thường theo chính sách dự án |
| first_name | Tối đa 30 ký tự; phần tên có thể gồm tên đệm; bắt buộc, không chỉ có khoảng trắng, trim trước khi lưu |
| last_name | Tối đa 30 ký tự; phần họ; bắt buộc, không chỉ có khoảng trắng, trim trước khi lưu |
| phone | Tùy chọn, tối đa 30 ký tự sau trim, lưu dạng chuỗi để giữ số 0 đầu và dấu +; bỏ trống thành NULL; không unique và chưa dùng đăng nhập/SMS |
| Mật khẩu người dùng nhập | Tối thiểu 15 Unicode code point và tối đa 72 byte UTF-8 sau chuẩn hóa, phù hợp BCrypt; cho phép dấu cách và Unicode; không trim, không đổi hoa/thường, không cắt ngắn |
| password_hash và pending_password_hash | BCrypt; dung lượng cột 255 ký tự để chứa hash đã mã hóa, gồm salt/cost; không cần cột mật khẩu rõ hoặc salt riêng |
| avatar_media_id | UUID tùy chọn; chỉ nhận ID ảnh hợp lệ thuộc người dùng, đã sẵn sàng sử dụng; bỏ ảnh thì NULL để giao diện dùng ảnh mặc định |

### Quy tắc email

- Giữ email đã trim để hiển thị/gửi thư; dùng khóa so sánh không phân biệt hoa/
  thường nhất quán ở unique index, tra cứu user, đăng ký, đăng nhập, quên mật khẩu
  và subject_hash của D17. Không thêm cột email_canonical chỉ để lặp lại dữ liệu
  nếu khóa so sánh có thể được tính nhất quán.
- Ví dụ Huy@example.com và huy@example.com được coi là cùng một định danh trong
  ứng dụng. Đây là lựa chọn so sánh của dự án, không khẳng định mọi nhà cung cấp
  email đều coi local-part là không phân biệt hoa/thường.
- Không tự bỏ dấu chấm hoặc phần +tag theo quy tắc riêng của Gmail/nhà cung cấp.
  Kiểm tra cú pháp bằng thư viện có kiểm chứng; OTP vẫn là bước chứng minh sở
  hữu địa chỉ theo D03, không suy ra quyền sở hữu chỉ từ định dạng đúng.

### Quy tắc mật khẩu

- Cùng một chính sách cho đăng ký, đổi mật khẩu và đặt lại mật khẩu. Không bắt
  buộc phối hợp chữ hoa/chữ thường/số/ký tự đặc biệt; kiểm tra mật khẩu
  quá phổ biến/đã lộ bằng danh sách chặn phù hợp, không gửi mật khẩu rõ tới dịch
  vụ bên ngoài.
- Xử lý Unicode NFC nhất quán trước khi đếm và hash; luôn kiểm tra toàn bộ giá
  trị, không trim hoặc cắt theo giới hạn byte của encoder. Tối thiểu 15
  Unicode code point, đồng thời không vượt 72 byte UTF-8. Giới hạn byte của
  BCrypt thay đề xuất tối đa 128 ký tự trước đó; không hiểu 72 byte là 72 ký tự
  Unicode. Độ dài chuỗi hash trong database không phải giới hạn mật khẩu đầu vào.
- Khi vượt 72 byte, backend từ chối với lỗi rõ ràng và frontend hiển thị cùng
  quy tắc; không âm thầm lấy 72 byte đầu hoặc tự thêm một bước pre-hash để tiếp
  tục nhận mật khẩu dài. Mọi luồng đặt/đổi/kiểm tra mật khẩu dùng cùng xử lý.
- Mật khẩu mới phải khác mật khẩu hiện tại; không bổ sung bảng lịch sử mật khẩu
  hoặc quy tắc cấm dùng lại N mật khẩu cũ. Không bắt đổi định kỳ chỉ vì đã qua
  một số ngày; các luồng OTP và thu hồi phiên đã duyệt vẫn giữ nguyên.
- BCrypt là lựa chọn đã chốt của người dùng. Dự kiến dùng BCryptPasswordEncoder
  của Spring Security để sinh salt ngẫu nhiên và kiểm tra hash. Cost cần đo trên
  máy triển khai, không thấp hơn 10 theo hướng dẫn OWASP; D20 chưa chốt cost cụ
  thể hoặc cài encoder/dependency.

### Ràng buộc hồ sơ và dữ liệu đang chờ

- Hồ sơ chỉ tự sửa first_name, last_name, phone, avatar theo phạm vi đã có; email giữ cố định,
  role/status và các trường bảo mật do backend quản lý. Chưa thêm ngày sinh,
  giới tính, địa chỉ hoặc trường cá nhân khác.
- Không dùng regex chỉ cho phép tên ASCII. Hai trường họ/tên cho phép tiếng Việt,
  dấu cách trong tên và không cần duy nhất. Từ chối chuỗi chỉ có khoảng
  trắng hoặc chứa ký tự điều khiển. Khi cần hiển thị tên đầy đủ, ghép hai trường
  theo quy ước giao diện; không lưu thêm display_name có nguy cơ lệch dữ liệu.
  Không thêm bảng/trường middle_name; tên đệm có thể nằm trong first_name. Phone
  không phải định danh đăng nhập hay bằng chứng đã được xác minh.
- D14 cho phép users chờ xác minh chưa có tên/hash mật khẩu cuối cùng. Các yêu
  cầu đăng ký phải giữ pending_first_name/pending_last_name/pending_password_hash hợp lệ; khi
  xác minh thành công mới ghi dữ liệu đã được chứng minh vào users. Không đổi
  các cột thành NOT NULL cho mọi trạng thái chỉ vì bảng ràng buộc đầu vào ở trên.
- Khi đã xác minh, tài khoản phải có tên và hash hợp lệ, kể cả sau khi chuyển
  LOCKED/DISABLED. Việc xóa giá trị xác thực tạm sau kết thúc theo D19 cần được
  phản ánh bằng nullability/constraint phù hợp, không xóa password_hash hiện hành.
- Avatar được kiểm tra quyền sở hữu và trạng thái media ở backend. Loại file,
  giới hạn kích thước và quy trình upload/dọn ảnh sẽ được duyệt ở nhóm học liệu/
  media; D20 không mở endpoint upload hoặc bổ sung SQL media.

Nguồn đối chiếu: NIST SP 800-63B-4 về độ dài tối thiểu mật khẩu cho đăng nhập một
yếu tố, không bắt composition rule và cách xử lý Unicode; OWASP về chính sách
so sánh email, giới hạn đầu vào/cost của BCrypt; Spring Security về encoder.
BCrypt và giới hạn 30 ký tự cho từng phần tên là lựa chọn của người dùng. Các
quy tắc khác của dự án không mặc nhiên trở thành yêu cầu của những nguồn này.

- https://pages.nist.gov/800-63-4/sp800-63b.html#passwordver
- https://cheatsheetseries.owasp.org/cheatsheets/Email_Validation_and_Verification_Cheat_Sheet.html#email-canonicalization
- https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html#input-limits-of-bcrypt
- https://docs.spring.io/spring-security/reference/api/java/org/springframework/security/crypto/bcrypt/BCryptPasswordEncoder.html

D20 đã được duyệt sau hai điều chỉnh: tách họ/tên tối đa 30 ký tự mỗi trường
và dùng BCrypt. Bản tổng hợp nhóm Account/Identity được ghi ở
ACCOUNT_BASELINE_REVIEWED.md. Chỉ cập nhật tài liệu; chưa sửa validation,
PasswordEncoder, entity, unique index hoặc migration. Việc chuyển thành schema
vật lý, đối chiếu Flyway history và kiểm thử vẫn thuộc bước triển khai sau rà soát.

## D21 Cách lưu học liệu đã được duyệt tạm thời, có hướng mở rộng video và chat

Người dùng xác nhận ngày 25/10/2026 theo điều kiện mở rộng: "Ví dụ sau này thêm
1 kiểu luyện bài xem 1 đoạn video ngắn rồi nhập chat trong đấy có update đc k
nếu được thì chốt tạm thời". Ghi nhận duyệt tạm thời cách lưu hiện tại và yêu cầu
thiết kế có thể mở rộng video kết hợp trả lời bằng text/hội thoại về sau.

Tiếp theo nhóm Account là học liệu/nội dung theo trình tự đã thống nhất. D02 đã
duyệt ba loại text, ảnh, audio, nhiều học liệu có thứ tự trong một nhóm câu hỏi,
và học liệu riêng cho câu con/câu độc lập. D21 không mở lại các quyết định đó;
chốt tạm cách lưu và dùng lại trước khi duyệt trường cụ thể của bảng media.

| Loại dữ liệu | Cách lưu đã duyệt tạm thời |
|---|---|
| TEXT | Nội dung văn bản lưu trong database; định dạng biên soạn/render được duyệt ở bước nội dung |
| IMAGE | File ảnh trong kho lưu trữ đối tượng; database lưu metadata và khóa định vị file |
| AUDIO | File âm thanh trong kho lưu trữ đối tượng; database lưu metadata và khóa định vị file |

- Dùng media_assets quản lý file IMAGE/AUDIO và avatar. TEXT không bị ép thành
  một file upload. Loại học liệu và mục đích file (CONTENT/AVATAR) là hai chiều
  khác nhau; avatar chỉ dùng ảnh theo D20.
- Mỗi file có media ID ổn định. Liên kết học liệu giữ media ID; bảng media giữ
  bucket/object_key cùng metadata. Không lưu file nhị phân/base64 trong PostgreSQL,
  không lấy URL tạm có chữ ký làm định danh lâu dài của học liệu hoặc snapshot.
- Một file được phép dùng ở nhiều nội dung nếu quyền sử dụng phù hợp. File không
  mang một question_id duy nhất để ép quan hệ 1:1; quan hệ và thứ tự trình bày
  thuộc nội dung sử dụng file. D21 chưa chọn bảng liên kết cụ thể.
- Thay file đã được tham chiếu bằng cách tạo media ID/object_key mới. Không ghi
  đè nội dung file cũ làm thay đổi bài học hoặc lượt làm đã ghi nhận. Dữ liệu
  lịch sử cần giữ tham chiếu được kiểm tra trước khi archive/xóa; chưa tự dọn file
  theo chính sách 30 ngày của dữ liệu xác thực ở D19.
- Mặc định file học liệu được lưu riêng tư. Backend kiểm tra quyền truy
  cập nội dung trước khi cấp URL đọc có thời hạn; URL có thể được dùng lại đến
  khi hết hạn, không được coi là mã dùng một lần hoặc sao chép vào audit/log.
- Chỉ file được kiểm tra hợp lệ và ở trạng thái sẵn sàng mới được gắn làm avatar
  đang sử dụng hoặc đưa vào nội dung đã công bố. Luồng upload, trạng thái,
  định dạng/dung lượng và phạm vi quyền biên soạn sẽ được duyệt ở các bước sau.
- Transcript/lời giải không tự động được trả về cùng media chỉ vì file audio
  được truy cập; chính sách hiển thị sẽ đối chiếu cùng chế độ luyện/chấm điểm.
  Phạm vi hiện tại vẫn là text/ảnh/audio; video và nhập chat được ghi nhận là
  hướng mở rộng tương lai bên dưới, chưa tự thêm vào bản đầu.

### Hướng mở rộng video ngắn kết hợp nhập chat

- Tách học liệu, kiểu bài/tương tác, dữ liệu lượt làm và cách chấm thành các
  trách nhiệm riêng. VIDEO là loại học liệu, không đồng thời định nghĩa người
  học phải trả lời thế nào hoặc hội thoại được chấm ra sao.
- File video có thể dùng cùng mô hình kho lưu trữ và media_assets, mở rộng loại
  file cùng metadata cần thiết qua migration. Không đóng bảng media vào tên
  image_assets/audio_assets hoặc ép mỗi file chỉ thuộc một question. Chọn định
  dạng, upload/xử lý/phát video và hạn mức dung lượng là công việc của tính năng
  sau này, không suy ra rằng lưu được file là đã phát video được trong ứng dụng.
- Phần bài luyện liên kết video với hướng dẫn/ngữ cảnh. Một câu trả lời text
  đơn có dữ liệu khác với chat nhiều lượt. Nếu là chat nhiều lượt, cần mô hình
  hội thoại/tin nhắn có thứ tự và gắn đúng user/lượt luyện; chưa chốt tên bảng
  hoặc dùng một cột answer_text duy nhất để giả lập toàn bộ lịch sử hội thoại.
- Chấm theo đáp án mẫu, đánh giá hội thoại hoặc phản hồi khác phải được duyệt
  theo nghiệp vụ khi bổ sung tính năng. Không mặc nhiên coi chat là chat với AI,
  giáo viên hay người dùng khác; AI vẫn ngoài phạm vi hiện tại theo quyết định cũ.
- Khi thực hiện sẽ cần migration phù hợp cho loại học liệu/kiểu tương tác mới,
  cập nhật validation, API, FE và kiểm thử. Các CHECK constraint, snapshot và
  cấu trúc đáp án của SQL cũ chưa hỗ trợ sẵn toàn bộ video/chat. Không cam kết
  rằng chỉ thêm một giá trị VIDEO là tính năng tự chạy.
- Giữ media ID/bản nội dung và hợp đồng dữ liệu của lượt làm cũ để migration
  tương thích với lịch sử. Mục tiêu là mở rộng từng phần, không thay toàn bộ
  database chỉ để bổ sung một kiểu bài. Chi tiết snapshot/chấm sẽ duyệt ở nhóm
  Question/Attempt; không tạo trước các bảng chat khi chức năng chưa được chốt.

Tham khảo mô hình object key và truy cập có thời hạn trong tài liệu AWS S3; đây
là đối chiếu cơ chế, chưa quyết định nhà cung cấp kho lưu trữ hay tạo dịch vụ:

- https://docs.aws.amazon.com/AmazonS3/latest/userguide/UsingObjects.html
- https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-presigned-url.html

D21 đã được duyệt tạm thời ở mức nguyên tắc lưu và dùng lại học liệu, với hướng
mở rộng video/chat. Các trường media_assets và thiết kế tính năng tương lai chưa
được chốt. Chỉ ghi tài liệu; chưa sửa SQL, khởi tạo bucket, tải file hoặc thay
đổi quyền truy cập hệ thống thật.

## D22 Dữ liệu bảng media_assets đã được xác nhận

Người dùng xác nhận ngày 25/10/2026: "Ok duyệ" đối với dữ liệu và bốn trạng thái
của media_assets. Tiếp theo D21, bảng chỉ quản lý định danh, vị trí, thông
tin kỹ thuật, nguồn và vòng đời file. Nội dung text, cấu trúc bài luyện, thứ tự
học liệu và câu trả lời không nằm trong bảng này.

| Trường | Ý nghĩa đã chốt |
|---|---|
| id | UUID định danh media, dùng làm tham chiếu ổn định |
| media_type | IMAGE hoặc AUDIO trong bản đầu; bổ sung VIDEO khi triển khai tính năng tương lai theo D21 |
| purpose | CONTENT hoặc AVATAR, mục đích sử dụng/phân quyền; AVATAR chỉ nhận IMAGE |
| bucket | Tên kho chứa file trong cấu hình lưu trữ của môi trường |
| object_key | Khóa file do backend quản lý, không dùng URL tạm làm giá trị này |
| original_filename | Tên file gốc phục vụ hiển thị trong quản lý upload |
| mime_type | MIME đã được backend kiểm tra, ví dụ image/jpeg hoặc audio/mpeg |
| file_size_bytes | Dung lượng file thực tế theo byte |
| duration_ms | Thời lượng audio theo mili giây; trống với ảnh |
| width | Chiều rộng ảnh theo pixel; trống với audio |
| height | Chiều cao ảnh theo pixel; trống với audio |
| checksum_sha256 | SHA-256 của file để đối chiếu toàn vẹn, có thể trống trước xác minh |
| source_id | Tham chiếu nguồn học liệu khi áp dụng; bảng nguồn/quy tắc bắt buộc được duyệt ở nhóm nguồn nội dung, avatar có thể để trống |
| uploaded_by | FK users, người khởi tạo tải file, backend xác định từ tài khoản hợp lệ |
| status | PENDING, READY, FAILED hoặc ARCHIVED |
| failure_code | Mã lỗi xử lý khi FAILED để giao diện giải thích được; không lưu stack trace, token hay URL có chữ ký |
| archived_at | Mốc archive ổn định để xét hạn dọn file, được bổ sung theo D26 |
| created_at | Thời điểm khởi tạo bản ghi |
| updated_at | Thời điểm cập nhật thông tin hoặc trạng thái |

### Ý nghĩa trạng thái đã được xác nhận

| Trạng thái | Ý nghĩa |
|---|---|
| PENDING | Đang chờ upload/xác minh, chưa được sử dụng trong nội dung công bố |
| READY | File đã qua kiểm tra và sẵn sàng sử dụng theo quyền truy cập |
| FAILED | Upload hoặc xác minh thất bại, có failure_code, không được sử dụng |
| ARCHIVED | Ngừng cho chọn/gắn mới; không tự xóa file hoặc làm mất quyền xem lịch sử còn hợp lệ |

- Unique(bucket, object_key) khi đã có vị trí file. Một media ID trỏ tới một
  đối tượng ổn định, có thể được nhiều nội dung tham chiếu theo quyền/purpose.
  Không unique checksum_sha256 toàn hệ thống để vô tình gộp file khác mục đích/
  quyền truy cập chỉ vì byte giống nhau; không cần question_id trong media_assets.
- Với PENDING, các metadata chưa đo được có thể trống; không buộc điền số 0,
  MIME giả hoặc checksum giả để qua NOT NULL. READY phải có vị trí file,
  MIME/dung lượng đã xác minh, file_size_bytes > 0 và checksum hợp lệ. Ảnh cần
  width/height > 0, audio cần duration_ms > 0. Nullability/CHECK cuối cùng sẽ
  được đồng bộ theo trạng thái khi chuyển sang SQL.
- media_type/purpose phải phù hợp quyền upload và kết quả kiểm tra file. MIME,
  kích thước, thời lượng, kích thước ảnh và checksum thực tế do backend xác
  nhận; không tin các giá trị frontend tự khai báo là bằng chứng file hợp lệ.
- original_filename chỉ để hiển thị, được kiểm tra độ dài/ký tự khi nhập và
  escape khi hiển thị; không dùng trực tiếp làm đường dẫn/object_key. Backend
  tạo và kiểm soát khóa file để giữ nguyên tắc không ghi đè file đã tham chiếu.
- failure_code mô tả lỗi bằng mã có kiểm soát; giao diện ánh xạ thành thông báo.
  Mã có thể trống ngoài FAILED. Lỗi không đồng nghĩa được phép xóa file đang
  được nội dung khác hoặc lịch sử tham chiếu.
- Giữ phân biệt CONTENT/AVATAR để kiểm tra quyền: người học có thể upload avatar
  của chính mình; quản trị nội dung upload học liệu bằng CONTENT_ADMIN. Role
  SYSTEM_ADMIN không tự kế thừa quyền biên soạn theo D07. Chi tiết ai được chọn/
  tái sử dụng file và luồng upload sẽ được duyệt riêng.
- Theo D21, file học liệu mặc định riêng tư. Chưa thêm chức năng chuyển file
  thành public chỉ vì SQL cũ có access_level; quyền cấp URL đọc phải được kiểm
  tra theo nội dung/lịch sử và mục đích sử dụng.
- Không thêm bảng riêng cho ảnh/audio. Khi bổ sung video, mở rộng media_type,
  validation và metadata phù hợp; không tạo trước streaming/transcoding hoặc
  bảng hội thoại. bitrate_kbps của SQL cũ chưa được giữ lại như trường bắt buộc
  khi chưa có truy vấn/nghiệp vụ cần dùng.
- source_id là nhu cầu truy xuất nguồn từ bản thiết kế cũ, chưa xác nhận schema
  bảng nguồn hay tự gán license. Chính sách nguồn dùng chung và điều kiện công
  bố sẽ được duyệt ở nhóm Content; không mặc nhiên coi mọi file là được công bố
  chỉ vì đạt READY về kỹ thuật.

Tham khảo nguyên tắc kiểm tra file thực tế, không tin Content-Type do client
gửi và quản lý tên file bằng ứng dụng:
https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html

D22 đã được duyệt ở mức dữ liệu và ý nghĩa trạng thái. Giới hạn định dạng/
dung lượng, luồng upload/xác minh, archive và dọn vật lý sẽ được duyệt tiếp.
Chỉ bổ sung tài liệu; chưa sửa SQL media_assets, entity hoặc dịch vụ lưu file.

## D23 Luồng upload và xác minh file đã được xác nhận

Người dùng xác nhận ngày 25/10/2026: "ok duyệt" đối với luồng upload có thời
hạn 10 phút cùng hạn mức/định dạng của D24. Luồng chung cho ảnh học liệu, audio
và avatar theo D21/D22. Người dùng
chỉ nhận được file có thể sử dụng sau khi backend xác minh, không chuyển READY
chỉ vì frontend báo tải lên thành công.

Ngày 25/10/2026, người dùng yêu cầu bắt buộc: "Nhớ phải có kiểm tra đầu vào
giới hạn dung lượng đấy nhé". Bổ sung kiểm tra đầu vào và chặn dung lượng trong
đường upload theo D24, không chỉ kiểm tra sau khi file đã được tải xong. Yêu cầu
bắt buộc này đã được ghi nhận, sau đó các con số hạn mức và hạn upload được
duyệt trong lượt xác nhận D23/D24 nêu trên.

1. Chọn file và mục đích. Backend kiểm tra tài khoản ACTIVE/đã xác minh, quyền
   upload, loại file và dung lượng khai báo theo cấu hình. Upload CONTENT cần
   CONTENT_ADMIN; avatar thuộc chính tài khoản đang đăng nhập, không cần quyền
   quản trị nội dung. SYSTEM_ADMIN không tự có quyền upload CONTENT nếu thiếu role.
2. Tạo yêu cầu upload, định danh media ở PENDING và khóa file tạm do backend sinh.
   Yêu cầu có hạn 10 phút từ lúc tạo; URL upload không vượt hạn yêu cầu.
   Nội dung URL/key, mục đích và người sở hữu được backend ràng buộc, không nhận
   object_key đích tùy ý do frontend cung cấp.
3. Frontend upload vào vùng tạm riêng tư bằng URL có thời hạn. File tạm chưa
   được cấp cho người học hoặc coi là học liệu đã sẵn sàng. Không upload trực
   tiếp bằng URL có thể ghi lại lên key cuối của file đã được xác nhận.
   Đường nhận file phải áp dụng giới hạn byte được backend cho phép theo D24,
   không để frontend bỏ kiểm tra là có thể tải file không giới hạn.
4. Upload xong, frontend gọi complete với ID yêu cầu. Backend kiểm tra quyền,
   chủ sở hữu và hạn yêu cầu rồi nhận xử lý xác minh. Yêu cầu complete phải tới
   trước hạn; khi đã được nhận xử lý hợp lệ, URL hết hạn không tự hủy tác vụ đang
   xác minh. Dữ liệu trạng thái/thời hạn xử lý được lưu để phục hồi nếu gián đoạn.
5. Backend xác minh file thực tế: tồn tại, MIME/định dạng cho phép, dung lượng,
   khả năng đọc/giải mã, thời lượng hoặc kích thước ảnh và checksum. Dữ liệu
   được đưa sang key cuối phải đúng bản file đã kiểm tra; cần ghim phiên bản/
   điều kiện nguồn hoặc sao chép chính bytes đã xác minh, không kiểm tra một
   bản rồi dùng bản khác vừa bị ghi đè trong vùng tạm.
6. Khi file cuối sẵn sàng, backend cập nhật metadata thực tế, chuyển media sang
   READY và hoàn tất yêu cầu upload bằng cập nhật database an toàn khi có retry/
   request đồng thời. Sau đó người dùng mới chọn/gắn file vào nội dung hoặc avatar.

### Thời hạn, thử lại và lỗi

- Hạn 10 phút là lựa chọn đã được duyệt của dự án, không phải thời hạn bắt buộc
  của nhà cung cấp. Hạn cho tác vụ đang xác minh/lease cần cụ thể hóa cùng mô
  hình trạng thái upload, không để tác vụ treo PENDING vô hạn.
- Gọi complete lặp sau thành công trả lại cùng media ID/kết quả theo quyền truy
  cập hiện có; không tạo thêm asset hoặc khởi động lại một file READY. Lần đang
  xác minh trả trạng thái đang xử lý để giao diện tiếp tục theo dõi.
- Hết hạn trước khi complete được nhận, upload bị hủy hoặc file không hợp lệ:
  kết thúc yêu cầu cũ, media chưa READY chuyển FAILED với mã lỗi phù hợp. Thử
  upload lại tạo yêu cầu/media/key tạm mới, không tái sử dụng URL cũ hoặc sửa
  file đã READY/ARCHIVED. Thử lại không tự vượt giới hạn upload theo cấu hình.
- Lỗi tạm do mạng/kho file khác với lỗi file không hợp lệ. Giao diện được xem
  trạng thái và thử lại an toàn; backend không vội ghi FAILED chỉ vì frontend
  mất phản hồi trong khi file/yêu cầu có thể đã được xử lý thành công.
- Quyền phải còn hợp lệ khi hoàn tất; không tiếp tục cấp media READY qua một
  yêu cầu cũ để vượt việc tài khoản đã bị khóa hoặc thu hồi quyền upload.
- Kho file và database không cùng transaction. Không giữ transaction/khóa
  database xuyên suốt việc truyền file. Khi có crash giữa các bước, dùng trạng
  thái bền vững và đối chiếu để retry/dọn file mồ côi; không công bố metadata
  READY trỏ tới file chưa xác minh hoặc xóa file đã được tham chiếu.
- Vùng tạm và key cuối tách biệt; URL upload có thể được dùng lại tới khi hết
  hạn, nên hoàn tất trong database không đồng nghĩa URL đó đã bị thu hồi. Giữ
  nguyên tắc file đã xác nhận không bị ghi đè theo D21 khi triển khai cơ chế lưu.

Luồng này cần dữ liệu yêu cầu upload gồm chủ sở hữu, media đích, vị trí tạm,
hạn và trạng thái xử lý. Bảng/trường cụ thể cùng quy tắc phục hồi sẽ được duyệt
tiếp; D23 chưa mặc định thêm một bảng cho mỗi bước. Định dạng/dung lượng tối đa
đã duyệt ở D24; archive và lịch dọn file tạm/vĩnh viễn còn cần duyệt riêng.

Nguồn đối chiếu về kiểm tra file và tính chất của URL upload có hạn:

- https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html
- https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-presigned-url.html

D23 đã được duyệt ở mức luồng và hạn nhận complete 10 phút. Chỉ cập nhật tài
liệu; chưa tạo URL upload, tác vụ xác minh, bucket, SQL hoặc thay đổi file thực tế.

## D24 Kiểm tra đầu vào, định dạng và hạn mức upload đã được xác nhận

Ngày 25/10/2026, người dùng trực tiếp yêu cầu có kiểm tra đầu vào và giới hạn
dung lượng upload; sau đó xác nhận "ok duyệt" đối với các định dạng, mốc dung
lượng và giới hạn tên file bên dưới, cùng luồng upload 10 phút ở D23.

| Mục đích/loại file | Định dạng đã chốt | Dung lượng tối đa mỗi file |
|---|---|---|
| AVATAR / IMAGE | JPEG, PNG, WebP | 2 MiB = 2.097.152 byte |
| CONTENT / IMAGE | JPEG, PNG, WebP | 5 MiB = 5.242.880 byte |
| CONTENT / AUDIO | MP3, M4A | 20 MiB = 20.971.520 byte |

MiB dùng đúng 1.048.576 byte; kiểm tra bằng số byte, không làm tròn MB hoặc chỉ
so sánh nhãn dung lượng trên giao diện. Mỗi file phải có dung lượng lớn hơn 0.
Hạn mức chỉ áp dụng các loại hiện tại; video/chat vẫn là hướng mở rộng D21,
chưa cho phép upload video/PDF/ZIP chỉ vì có thể lưu chúng vào kho file.

### Các điểm kiểm tra bắt buộc

1. Frontend kiểm tra file chọn và hiển thị lỗi ngay để người dùng sửa; không
   xem kiểm tra frontend là điều kiện đủ để cho phép upload.
2. Trước khi cấp quyền/link upload, backend kiểm tra schema đầu vào, media_type/
   purpose, định dạng khai báo, dung lượng nguyên dương trong hạn mức, tên file,
   nguồn nếu có và quyền của tài khoản. original_filename tối đa 255
   ký tự, không rỗng/ký tự điều khiển hoặc dùng như một đường dẫn. Không nhận
   actor_user_id/object_key/status đích tùy ý từ frontend để bỏ qua kiểm tra.
3. Đường nhận file phải ràng buộc dung lượng thực tế theo yêu cầu đã được cấp.
   Với upload trực tiếp, chọn policy/cơ chế lưu trữ có thể cưỡng chế giới hạn;
   nếu nhà cung cấp/cách ký không đáp ứng thì dùng đường upload có bộ đếm byte
   và dừng truyền khi vượt mức. Không chỉ dựa Content-Length do client khai
   báo hoặc đợi tải file quá lớn xong mới từ chối ở complete. Nếu dùng multipart,
   giới hạn tổng dung lượng file, không chỉ từng phần.
4. Khi complete, backend xác minh lại đúng bản file được dùng: dung lượng thực
   tế phải khớp khai báo và không vượt trần, MIME/chữ ký file/khả năng giải mã
   phù hợp định dạng cho phép, checksum và thông số ảnh/audio hợp lệ. Đổi đuôi
   tên file hoặc MIME client gửi không làm file trở thành hợp lệ. Áp dụng thêm
   giới hạn tài nguyên giải mã (pixel/thời gian/bộ nhớ) khi triển khai để không
   suy ra file nén nhỏ thì chi phí xử lý luôn nhỏ.

- Trước khi khởi tạo upload, dữ liệu sai bị từ chối với lỗi tại trường và không
  cấp quyền tải. Nếu phát hiện vi phạm sau khi đã tạo yêu cầu, kết thúc yêu cầu/
  media chưa READY theo trạng thái FAILED, mã có kiểm soát như FILE_TOO_LARGE,
  UNSUPPORTED_FILE_TYPE hoặc INVALID_FILE. Không chuyển READY cho file vi phạm.
- Giao diện báo rõ loại file, hạn mức và lỗi để sửa; ánh xạ lỗi từ kho file phù
  hợp, không coi mọi lỗi mạng/quyền truy cập là lỗi quá dung lượng. Retry vẫn
  phải qua cùng kiểm tra đầu vào và giới hạn.
- Hạn mức đặt ở cấu hình backend làm nguồn chuẩn; frontend lấy hoặc đồng bộ
  cùng chính sách. Ghi hạn mức được cấp vào ngữ cảnh yêu cầu upload để backend
  kiểm tra nhất quán khi complete; không để frontend tự chọn max_size.
- Giới hạn số lượt upload, số yêu cầu đồng thời, quota tổng và lịch dọn vùng
  tạm sẽ thiết kế cùng vận hành upload; giới hạn từng file không thay cho chúng.
  D24 chưa tự chốt những con số bổ sung này.

Nguồn đối chiếu nguyên tắc kiểm tra loại file/dung lượng: OWASP File Upload.
Ví dụ AWS S3 POST policy có điều kiện content-length-range; khi triển khai phải
đối chiếu đúng khả năng nhà cung cấp đã chọn, không giả định mọi URL PUT hoặc
mọi dịch vụ tương thích S3 đều hỗ trợ cùng policy.

- https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html#upload-and-download-limits
- https://docs.aws.amazon.com/AmazonS3/latest/developerguide/sigv4-HTTPPOSTConstructPolicy.html

D24 đã được duyệt: kiểm tra đầu vào bắt buộc, bảng định dạng, các mức 2/5/20 MiB
và giới hạn tên file 255 ký tự. Luồng và hạn upload 10 phút ở D23 được duyệt
cùng lượt. Chỉ cập nhật tài liệu, chưa cấu hình hoặc triển khai kiểm tra thực tế.

## D25 Dữ liệu yêu cầu upload đã được xác nhận

Người dùng xác nhận ngày 25/10/2026: "ok chốt đi" đối với bảng, trạng thái và
hai mốc xử lý 5 phút/60 giây. media_upload_requests giữ ngữ cảnh upload theo D23/
D24. media_assets mô tả file dùng lâu dài; yêu cầu upload giữ dữ liệu tạm về
vị trí nhận file, dung lượng được cấp, hạn và việc xác minh. Không thêm bảng
riêng cho từng bước tải/kiểm tra/sao chép.

| Trường | Ý nghĩa đã chốt |
|---|---|
| id | UUID định danh yêu cầu, dùng cho complete và xem trạng thái |
| media_id | FK media_assets, duy nhất; mỗi yêu cầu gắn một media đã khởi tạo |
| temp_bucket | Kho nhận file tạm |
| temp_object_key | Khóa file tạm do backend sinh, không trùng với key cuối đã xác nhận |
| declared_size_bytes | Dung lượng client khai báo, phải khớp file thực tế khi xác minh |
| declared_mime_type | MIME khai báo đã qua kiểm tra sơ bộ, chưa phải bằng chứng file thực tế hợp lệ |
| max_size_bytes | Hạn mức backend cấp cho yêu cầu theo purpose/type, không do client chọn |
| status | PENDING, VERIFYING, COMPLETED, FAILED hoặc EXPIRED |
| expires_at | Hạn nhận complete, 10 phút từ lúc tạo theo D23 |
| verification_started_at | Lần đầu backend chấp nhận complete và bắt đầu xác minh |
| verification_deadline_at | Hạn kết thúc toàn bộ quá trình xác minh, không gia hạn theo retry |
| claim_token | Mã lượt xử lý hiện tại, ngăn lượt xử lý cũ ghi kết quả sau khi đã được tiếp quản |
| lease_until | Hạn quyền xử lý tạm thời; cho phép tiếp quản khi bộ xử lý bị gián đoạn |
| completed_at | Thời điểm xác minh/hoàn tất thành công |
| failure_code | Mã lỗi có kiểm soát nếu thất bại/hết hạn |
| created_at | Thời điểm tạo yêu cầu |
| updated_at | Thời điểm cập nhật gần nhất |

### Quan hệ, trạng thái và giới hạn

- UNIQUE(media_id) và UNIQUE(temp_bucket, temp_object_key). Mỗi lần upload lại
  sau thất bại/hết hạn tạo yêu cầu và media mới theo D23. Không cần sao chép
  uploaded_by, media_type, purpose và tên file sang bảng này; lấy từ media_assets
  tương ứng và giữ ổn định ngữ cảnh trong suốt yêu cầu.
- Chủ sở hữu yêu cầu được xác định qua media_assets.uploaded_by. Mọi API complete/
  xem trạng thái phải kiểm tra chủ sở hữu và quyền hiện tại, không cho truy cập
  chỉ vì biết UUID. Không lưu signed URL/token kho file trong bảng.
- PENDING là chờ nhận complete; VERIFYING là backend đã nhận xử lý trước hạn;
  COMPLETED là kết thúc thành công; FAILED là kết thúc không hợp lệ/bị hủy hoặc
  không thể phục hồi; EXPIRED là hết hạn mà chưa được nhận xử lý. File ở trạng
  thái media_assets.PENDING trong lúc yêu cầu PENDING/VERIFYING.
- Yêu cầu COMPLETED đi cùng media READY trong transaction cập nhật database.
  FAILED/EXPIRED chỉ được chuyển media chưa READY thành FAILED với mã phù hợp;
  không kéo lùi file READY hoặc ARCHIVED vì một request cũ đến muộn.
- expires_at giới hạn lúc tiếp nhận complete, không dùng để dọn nhầm VERIFYING.
  Thời hạn xác minh tối đa 5 phút từ verification_started_at. Hết hạn
  xử lý thì kết thúc với mã VERIFICATION_TIMEOUT nếu chưa hoàn tất, không giữ
  PENDING vô hạn và không gia hạn hạn này khi retry/được tiếp quản.
- Lease xử lý 60 giây, được gia hạn khi tác vụ còn hoạt động nhưng
  không vượt verification_deadline_at. Khi lease hết, lượt mới có thể tiếp quản
  bằng cập nhật có điều kiện và claim_token mới. Lượt cũ chỉ được chốt kết quả
  nếu vẫn giữ token/lease hợp lệ; không giữ khóa database trong lúc truyền/
  giải mã file. Nếu có hai lượt xử lý, key cuối cần bảo đảm bất biến và chỉ
  đúng lượt có quyền mới công bố kết quả, phần file mồ côi được đối chiếu/dọn sau.
- Gọi complete lặp khi VERIFYING trả trạng thái đang xử lý; sau COMPLETED trả
  cùng media/kết quả theo quyền hiện có, kể cả hạn upload ban đầu đã qua. Lỗi
  mạng tạm chỉ được retry trong thời hạn xử lý còn lại; không coi mất phản hồi
  là thất bại khi database đã ghi nhận thành công.
- declared_size_bytes phải nguyên dương và không vượt max_size_bytes; metadata
  thực tế được ghi ở media_assets sau xác minh. Việc bảo đảm đúng bản file đã
  kiểm tra, chống ghi đè và cưỡng chế byte trong đường upload tuân theo D23/D24.
- claim_token/lease/thời gian xác minh có thể trống trước khi nhận complete.
  Ràng buộc thời gian, trạng thái kết thúc và completed_at/failure_code sẽ được
  chốt đồng bộ khi viết SQL; không ép metadata chưa biết thành giá trị giả.

Các mốc 5 phút xác minh và lease 60 giây là lựa chọn đã được duyệt cho quy trình
xử lý file hiện tại, không thay hạn upload 10 phút hoặc dung lượng D23/D24 đã
duyệt. Giới hạn đồng thời và quota upload, archive, retention yêu cầu/vùng tạm
còn cần duyệt ở bước vận hành media; không áp dụng tự động retention 30 ngày
của xác thực tài khoản cho yêu cầu upload.

D25 đã được duyệt ở mức bảng, trạng thái và thời hạn xử lý. Chỉ bổ sung tài
liệu; chưa tạo bảng media_upload_requests, job xác minh hoặc thay đổi file thực tế.

## D26 Archive và giữ/dọn dữ liệu media đã được xác nhận

Người dùng xác nhận ngày 25/10/2026: "có" đối với chính sách giữ/dọn, phạm vi
archive và bổ sung archived_at vào media_assets. Vòng đời file theo D21–D25:
hết hạn upload/xác minh là
điều kiện ngừng nhận thao tác; xóa file thực tế và giữ metadata là các bước
riêng. Không dùng chính sách dọn dữ liệu xác thực tài khoản thay cho media.

| Nhóm dữ liệu | Chính sách đã chốt |
|---|---|
| File tạm của yêu cầu COMPLETED/FAILED/EXPIRED | Đủ 24 giờ từ lúc yêu cầu kết thúc thì được dọn nếu URL upload đã hết hạn, không còn tác vụ xử lý hợp lệ và không phải file cuối được tham chiếu |
| File cuối mồ côi do xử lý gián đoạn, không được công bố thành READY | Chỉ dọn sau đối chiếu trạng thái/claim và đủ 24 giờ từ lúc tạo file; không suy ra file mồ côi chỉ vì một request mất phản hồi |
| Metadata media_upload_requests đã kết thúc và media FAILED | Giữ 30 ngày sau kết thúc, rồi dọn đúng thứ tự FK khi không còn file/yêu cầu cần đối chiếu |
| File READY đang được sử dụng hoặc có tham chiếu lịch sử | Giữ, không tự hết hạn chỉ vì đã qua 30 ngày |
| File CONTENT đã READY nhưng chưa gắn nội dung | Giữ trong thư viện cho tới khi được archive theo quyền; không tự xóa vì chưa được dùng |
| Avatar cũ sau khi đổi/bỏ ảnh; avatar READY chưa từng gắn profile | Archive avatar cũ khi không còn tham chiếu; avatar mới bỏ dở chưa gắn thì archive sau 7 ngày không sử dụng |
| File ARCHIVED | Được xét xóa vật lý sau 30 ngày từ lúc archive, chỉ khi không còn bất kỳ tham chiếu cần giữ và không còn xử lý hợp lệ |

### Archive và điều kiện xóa

- ARCHIVED ngừng cho chọn/gắn mới; các tham chiếu cũ còn hợp lệ vẫn được đọc
  theo quyền. Archive không tự xóa file, không sửa nội dung snapshot và không
  làm mất kết quả đã có. Không ghi đè media cũ khi thay file theo D21.
- Người dùng được archive avatar của chính mình không còn là ảnh hiện
  tại. CONTENT_ADMIN được archive file CONTENT trong thư viện theo phạm vi
  quyền biên soạn; SYSTEM_ADMIN không tự kế thừa quyền này. Không cung cấp nút
  xóa vật lý trực tiếp trong bản đầu; tác vụ dọn thực hiện theo chính sách.
- Bổ sung archived_at vào media_assets để lưu mốc archive ổn định,
  tính hạn 30 ngày; không dùng updated_at có thể bị thay đổi vì sửa metadata.
  Người thực hiện/lý do được ghi audit theo hợp đồng thao tác media sẽ đối chiếu
  cùng Content; không sao chép URL có chữ ký hoặc file bí mật vào audit.
- Kiểm tra tham chiếu phải bao gồm profile hiện tại, nội dung đang dùng, draft,
  revision đã giữ và snapshot/lịch sử lượt làm, kể cả tham chiếu nằm trong JSON.
  Khi chưa bảo đảm truy xuất đủ các tham chiếu lịch sử thì chưa bật tự động xóa
  file cuối; việc chỉ đếm FK của nội dung hiện hành không đủ chứng minh an toàn.
- Trước khi xóa phải kiểm tra lại trạng thái, hạn, tham chiếu và lease xử lý.
  Cơ chế gắn mới/archive/dọn cần phối hợp để không tạo tham chiếu sau khi đã
  nhận xóa. Không giữ transaction database xuyên suốt thao tác với kho file.
  Xóa và cập nhật metadata phải có trạng thái/đối chiếu để retry sau crash, không
  để file đã xóa vẫn được công bố READY hoặc xóa một file vừa được nhận xử lý.
- Metadata chỉ được xóa sau khi xử lý xong file liên quan và đủ điều kiện FK.
  Yêu cầu upload kết thúc không được heartbeat/cập nhật lại như đang chạy;
  mốc tính retention là completed_at với COMPLETED, hoặc updated_at của lần
  chuyển sang FAILED/EXPIRED đã được giữ ổn định. Nếu cần sửa bản ghi kết thúc
  ở hợp đồng sau này thì phải có mốc kết thúc riêng, không kéo dài hạn bằng sửa dữ liệu.
- Tác vụ dọn chạy hằng ngày. Các mốc 24 giờ/7 ngày/30 ngày là thời điểm
  sớm nhất đủ điều kiện; không cam kết file biến mất đúng ngay tại mốc khi
  còn chờ lần chạy kế tiếp hoặc còn tham chiếu/tác vụ hợp lệ.
- Backup và phục hồi phải xét đồng thời database, file và tham chiếu lịch sử;
  việc xóa khỏi dữ liệu đang chạy không tự quyết định vòng đời bản sao lưu.
  Chính sách vận hành backup được chốt trước triển khai, không cấu hình ở lượt này.

D26 đã được duyệt, gồm thời hạn giữ/dọn, phạm vi archive và archived_at. Bảng
dữ liệu D22 đã được bổ sung trường này trong tài liệu. Giới hạn số lượt/quota
upload vẫn còn mở. Chưa tạo tác vụ dọn, thêm cột SQL hoặc thực hiện archive/xóa
bất kỳ file hay bản ghi thực tế nào.

## D27 Giới hạn số lượt, yêu cầu đang mở và quota thư viện đã được xác nhận

Người dùng xác nhận ngày 25/10/2026: "Ok" đối với các giới hạn khởi điểm nêu
dưới đây. Đây là chính sách dự án đã được duyệt, không phải giới hạn mặc định
của Spring/AWS/OWASP. Hạn
upload 10 phút, thời hạn xác minh 5 phút, lease 60 giây và trần mỗi file 2/5/20
MiB theo D23–D25 vẫn giữ nguyên.

| Phạm vi | Avatar của mỗi user | Học liệu CONTENT |
|---|---|---|
| Tạo yêu cầu upload trong 60 phút gần nhất | Tối đa 10 yêu cầu/user | Tối đa 100 yêu cầu/CONTENT_ADMIN |
| Yêu cầu còn mở PENDING/VERIFYING | Tối đa 2/user | Tối đa 3/CONTENT_ADMIN |
| Quota dung lượng thư viện | 50 MiB/user | 10 GiB dùng chung toàn bộ thư viện CONTENT |

Toàn backend chỉ chạy tối đa 2 tác vụ xác minh file đồng thời lúc khởi
đầu. Đây là cấu hình điều tiết xử lý cần đo khi triển khai trên EC2, không phải
giới hạn mới về số người đăng nhập hoặc số bài được học. Yêu cầu đã được nhận
VERIFYING có thể chờ lượt xử lý trong cùng hạn 5 phút, không tự gia hạn vì chờ.

### Cách tính và hành vi

- Đếm số yêu cầu đã được backend tạo trong 60 phút trượt. Upload thất bại hoặc
  bị hủy vẫn tính lượt tạo; gọi complete/xem trạng thái lặp của cùng yêu cầu
  không tính thành upload mới. Không xóa lịch sử giới hạn chỉ vì tạo media ID mới.
- Yêu cầu PENDING còn trong hạn và VERIFYING chưa hết thời hạn xử lý chiếm
  một suất mở, kể cả ở tab khác. Lease hết nhưng còn được tiếp quản theo D25
  không làm mất suất mở. Yêu cầu đã hết hiệu lực không chiếm suất vĩnh viễn chỉ
  vì tác vụ dọn chưa cập nhật trạng thái; việc kiểm tra/tạo cần xử lý nhất quán.
- Quota thư viện là tổng file cuối READY và ARCHIVED chưa được xác nhận xóa,
  cộng dung lượng file cuối đang giữ chỗ cho yêu cầu còn hợp lệ. Mỗi media ID
  chỉ tính một lần dù nhiều bài dùng chung. Tính riêng AVATAR theo uploaded_by
  và CONTENT theo toàn thư viện, không cấp lại 10 GiB cho mỗi admin.
- Khi cấp upload, giữ chỗ theo declared_size_bytes đã kiểm tra; đường upload
  phải ràng buộc số byte của file được cấp, và complete chỉ chấp nhận kích thước
  khớp khai báo theo D24. Khi READY, chuyển giữ chỗ thành file thực tế, không
  tính đôi. Khi thất bại/hết hạn, giải phóng giữ chỗ file cuối sau đối chiếu để
  không bỏ sót một kết quả đã hoàn tất.
- ARCHIVED chưa làm giảm quota thư viện. Chỉ giải phóng phần file cuối sau khi
  đã xác nhận xóa đúng đối tượng và cập nhật dữ liệu an toàn; không tự xóa file
  lịch sử để dành chỗ cho upload mới. Không cho một lượt tải làm vượt trần do
  nhiều request đồng thời cùng thấy đủ chỗ; cần phối hợp transaction/giữ chỗ.
- Quota thư viện không đồng nghĩa trần byte vật lý hay hóa đơn kho lưu trữ:
  file tạm, bản sao khi xác minh, file lỗi/mồ côi còn chờ dọn và backup có thể
  tốn thêm dung lượng. Chúng cần ngân sách tạm/giới hạn tài nguyên, đối chiếu
  sử dụng và theo dõi chi phí khi cấu hình kho lưu trữ trước triển khai. Không
  lấy con số 10 GiB này để khẳng định tổng dịch vụ lưu trữ không thể vượt 10 GiB.
- Khi chạm số lượt: báo thời gian chờ phù hợp. Khi hết suất mở: báo đang có
  upload chưa kết thúc. Khi hết quota: báo dung lượng đã dùng/còn lại và từ
  chối yêu cầu mới; việc đọc học liệu đã có không bị ngừng chỉ vì hết quota.
- Lưu các hạn mức ở cấu hình backend và đồng bộ thông tin cho frontend. Quyền
  thay cấu hình được đối chiếu nhóm Administration; không tự thêm bộ máy gói
  thuê bao hoặc quota riêng tùy ý theo từng người dùng.
- Có thể tính/đối chiếu lượt, suất mở và dung lượng từ media_assets cùng
  media_upload_requests; ưu tiên dùng dữ liệu hiện có, không mặc định thêm
  một bảng bộ đếm lặp lại. Khi viết SQL cần chọn khóa/index theo truy vấn và
  xử lý giữ chỗ đồng thời, tránh chỉ dựa vào một phép SUM không có phối hợp.

Tham khảo nguyên tắc giới hạn tài nguyên, tần suất và cấu hình theo nhu cầu:
https://api-security.owasp.org/editions/2023/en/0xa4-unrestricted-resource-consumption/

D27 đã được duyệt: quota 50 MiB/10 GiB, giới hạn 10/100 lượt, 2/3 yêu cầu mở
và 2 tác vụ xác minh là cấu hình khởi điểm. Bản tổng hợp media ở
MEDIA_BASELINE_REVIEWED.md. Chưa áp dụng giới hạn vào API, tạo bộ đếm, thay
cấu hình dịch vụ hoặc mua dung lượng lưu trữ.

## D28 Dữ liệu nguồn học liệu đã được xác nhận

Người dùng xác nhận ngày 25/10/2026: "Ok rồi" đối với dữ liệu và nguyên tắc
quản lý nguồn. content_sources ghi xuất xứ và thông tin quyền sử dụng do người biên
soạn khai báo. Nguồn khác với vị trí lưu file trong bucket: một bài có thể dùng
văn bản tự soạn, ảnh từ một nguồn và audio từ nguồn khác. Cấu trúc này cũng áp
dụng cho nội dung text không có file upload.

| Trường | Ý nghĩa đã chốt |
|---|---|
| id | UUID định danh nguồn |
| title | Tên tài liệu/tác phẩm hoặc tên nguồn để tra cứu, tối đa 255 ký tự |
| source_type | SELF_AUTHORED hoặc EXTERNAL, phân biệt nội dung tự biên soạn với nguồn bên ngoài |
| source_url | Đường dẫn tới nguồn gốc nếu có; không phải URL upload/download tạm của kho file |
| author | Tác giả/đơn vị cung cấp theo thông tin nguồn, không bắt buộc là user trong hệ thống |
| license_code | Mã/tên ngắn của giấy phép hoặc hình thức cho phép sử dụng được khai báo |
| license_url | Đường dẫn thông tin giấy phép nếu có |
| attribution | Nội dung ghi công/ghi nguồn được khai báo cho tài liệu |
| permission_note | Ghi chú về phạm vi sử dụng hoặc bằng chứng cho phép, nếu áp dụng |
| status | ACTIVE hoặc ARCHIVED; trạng thái sử dụng trong thư viện, không phải kết luận pháp lý |
| created_by | FK users, người khai báo nguồn trong hệ thống |
| created_at | Thời điểm tạo bản ghi |
| updated_at | Thời điểm cập nhật |

- Nội dung tự soạn cũng khai báo nguồn SELF_AUTHORED, ghi tác giả/đơn vị phù
  hợp. created_by là người nhập, không tự đồng nhất người nhập với tác giả hoặc
  chủ thể có quyền cấp phép. Các giá trị giấy phép do người biên soạn khai báo,
  không tự gán một giấy phép CC cho nội dung chỉ vì chọn SELF_AUTHORED/EXTERNAL.
- Một nguồn được dùng lại ở nhiều file/nội dung. Một bản nội dung có thể tham
  chiếu nhiều nguồn; cách liên kết theo revision và thông tin ghi nhận tại thời
  điểm công bố sẽ được duyệt tiếp. Trường source_id của media_assets đang là
  một nguồn chính của file, không thay cho mọi nguồn của toàn bộ bài.
- CONTENT_ADMIN khai báo/cập nhật/archive nguồn theo phạm vi biên soạn;
  SYSTEM_ADMIN không tự kế thừa quyền nội dung theo D07. Không tạo actor/role
  reviewer mới chỉ để khai báo nguồn.
- Nguồn đang thiếu thông tin quyền sử dụng có thể được chuẩn bị trong Draft.
  Bước công bố kiểm tra khai báo nguồn/quyền sử dụng cần thiết; một URL
  hoặc file READY không tự thay cho khai báo này. Backend kiểm tra dữ liệu và
  lưu người khai báo, không tự đưa ra kết luận pháp lý về một tác phẩm cụ thể.
- ACTIVE chỉ có nghĩa nguồn còn được chọn dùng, không tự chứng minh mọi cách
  sử dụng đều được phép. ARCHIVED ngừng cho gắn mới, giữ bản ghi và tham chiếu
  cũ để truy xuất. Ảnh hưởng tới nội dung công bố khi nguồn bị thu hồi/thay đổi
  và cách giữ lịch sử sẽ được chốt cùng Content/revision, không xóa dây chuyền.
- Phân biệt field dữ liệu với nghiệp vụ: source_type chỉ phân loại xuất xứ;
  license_code/URL/permission_note mô tả khai báo sử dụng. Không dùng nhãn
  OPEN_DATA hoặc một enum source_type để suy ra mọi điều kiện quyền sử dụng.
- Avatar vẫn theo quyền sở hữu cá nhân ở D20/D22; D28 không mặc nhiên bắt người
  dùng tạo nguồn học liệu trước khi cập nhật ảnh đại diện.

Đối chiếu nhóm thông tin title/author/source/license với hướng dẫn ghi công
Creative Commons (TASL), không áp đặt mọi nguồn phải dùng giấy phép CC:
https://wiki.creativecommons.org/wiki/Recommended_practices_for_attribution

D28 đã được duyệt về dữ liệu và nguyên tắc quản lý nguồn. Chưa sửa SQL
content_sources, tạo giấy phép, công bố nội dung hoặc thay đổi quyền sử dụng.

## D29 Liên kết nguồn theo phiên bản nội dung đã được xác nhận

Người dùng xác nhận ngày 25/10/2026: "ok duyeet đi" đối với bảng nối, snapshot
nguồn và cách giữ nguồn riêng theo phiên bản. Dùng content_source_links để liên kết nhiều nguồn với một revision,
tức một phiên bản cụ thể của nội dung. Một bài có định danh ổn định nhưng có
thể có nhiều revision; cấu trúc content_items/content_revisions và vòng đời
biên soạn sẽ được duyệt tiếp. D29 chốt trước dữ liệu liên kết nguồn theo từng
bản thay vì ghi đè danh sách nguồn của mọi phiên bản vào một cột trên bài.

| Trường | Ý nghĩa đã chốt |
|---|---|
| revision_id | FK content_revisions, bản nội dung sử dụng nguồn |
| source_id | FK content_sources, nguồn được sử dụng |
| usage_note | Ghi chú nguồn được dùng vào phần nào, ví dụ đoạn đọc/ảnh/audio, và thay đổi/trích dùng nếu cần |
| source_snapshot | JSONB giữ thông tin nguồn được ghi nhận cho bản công bố, không tự thay bằng dữ liệu nguồn mới nhất |
| created_at | Thời điểm gắn nguồn vào revision |

- Khóa chính ghép (revision_id, source_id); không thêm UUID riêng cho bảng nối.
  Một revision có nhiều nguồn, một nguồn có thể dùng ở nhiều revision. Cùng
  nguồn dùng ở nhiều chỗ của một bản thì gộp ghi chú, không tạo dòng trùng khóa.
- Khi biên soạn Draft, cho thêm/bỏ nguồn và chỉnh usage_note. source_snapshot
  có thể chưa có hoặc chỉ là dữ liệu xem trước; lúc công bố phải kiểm tra lại
  nguồn và ghi bản chụp cuối cùng. Liên kết/snapshot của bản đã công bố bất biến
  qua thao tác biên soạn thông thường; muốn đổi tạo bản nội dung mới.
- Snapshot có cấu trúc/phiên bản dữ liệu xác định, gồm source ID, title,
  source_type, author, source_url, license_code, license_url, attribution và
  thông tin khai báo quyền cần lưu nội bộ. Mốc ghi nhận nguồn gắn với thời điểm
  công bố của revision, không dùng created_at của liên kết để suy ra mốc này.
- Khi sửa title/tác giả/khai báo trong content_sources, những lần sử dụng sau
  có thể lấy dữ liệu mới; bản đã công bố vẫn giữ thông tin từng ghi nhận. FK
  source_id vẫn bảo toàn đường truy xuất nguồn gốc, snapshot không thay cho FK.
- Ví dụ bài Grammar revision 1 dùng A; revision 2 dùng A và B. Revision 1 chỉ
  có liên kết A và snapshot của nó. Thêm B cho revision 2 không thêm B ngược
  vào revision 1 hoặc đổi dữ liệu nguồn đã ghi nhận ở revision 1.
- Danh sách nguồn của bản nội dung phải bao quát nguồn khai báo trực tiếp và
  nguồn của các media được dùng. media_assets.source_id là nguồn chính của file;
  khi công bố cần kiểm tra/tổng hợp để không bỏ nguồn ảnh/audio chỉ vì phần text
  đã khai một nguồn khác. Cách giao diện hỗ trợ gắn nguồn và tham chiếu từng
  material sẽ được cụ thể hóa cùng hợp đồng biên soạn/snapshot.
- Chỉ chọn nguồn ACTIVE cho liên kết mới; kiểm tra lại dữ liệu cần thiết lúc
  công bố. Archive nguồn không xóa liên kết/snapshot cũ. Thao tác thu hồi quyền
  sử dụng và ảnh hưởng tới phân phối nội dung phải được xử lý riêng theo trạng
  thái/quyền hiện tại, không suy ra một snapshot cũ cho phép truy cập vô điều kiện.
- Snapshot là dữ liệu lưu nội bộ của bản nội dung. API người học chỉ trả thuộc
  tính cần cho ghi nguồn/hiển thị; không tự trả permission_note, dữ liệu bằng
  chứng nội bộ hoặc toàn bộ JSON chỉ vì đã lưu trong source_snapshot.
- Ghi snapshot nguồn và công bố revision phải nhất quán trong transaction phù
  hợp, tránh ghi bản nội dung với thông tin nguồn không khớp lần kiểm tra. FK
  không tự bảo đảm tính bất biến, trạng thái ACTIVE hoặc đủ dữ liệu công bố;
  đây còn là điều kiện service/transaction khi triển khai.

D29 đã được duyệt về bảng nối, snapshot nguồn và tính độc lập giữa các bản
nội dung. Chưa sửa SQL content_source_links, tạo bảng revision hoặc áp dụng
cơ chế công bố/ghi snapshot vào hệ thống đang chạy.

## D30 Định danh nội dung và bản công bố đang đề xuất để duyệt

Đề xuất content_items giữ định danh ổn định của nội dung và trỏ tới revision
đang công bố. content_revisions sẽ giữ từng bản nội dung cụ thể và được duyệt
chi tiết ở bước tiếp theo. Bước này không gộp trường riêng của từ vựng, Grammar,
câu hỏi hoặc kiểu trả lời vào cùng bảng định danh.

| Trường | Ý nghĩa đề xuất |
|---|---|
| id | UUID định danh nội dung, giữ ổn định qua các revision |
| content_type | Loại nội dung: WORD, DECK, GRAMMAR_TOPIC, GRAMMAR_LESSON, QUESTION, QUESTION_GROUP hoặc PRACTICE_SET |
| created_by | FK users, người khởi tạo nội dung trong hệ thống |
| published_revision_id | Revision đang được công bố cho người học; có thể trống khi chưa từng công bố |
| is_archived | Ngừng đưa nội dung vào danh sách/bài luyện mới, không xóa lịch sử |
| version | Phiên bản khóa để chống cập nhật ghi đè trên bản ghi content_items |
| created_at | Thời điểm tạo định danh nội dung |
| updated_at | Thời điểm cập nhật bản ghi |

- Một content_item có nhiều revision. id và content_type là định danh/loại ổn
  định; sửa nội dung tạo hoặc cập nhật bản nháp theo vòng đời được duyệt tiếp,
  không đổi id của bài đã tồn tại. Không đổi một item từ WORD sang QUESTION
  để giữ cùng UUID nhưng mang nghĩa khác; tạo item mới khi đổi loại như vậy.
- Chưa từng công bố thì published_revision_id trống; trạng thái chưa archive
  không đủ để cấp nội dung cho người học. Người biên soạn có thể xem trước
  Draft theo quyền, còn API người học chọn bản công bố hợp lệ.
- published_revision_id phải thuộc chính item và là bản đã công bố, không trỏ
  sang revision của bài khác hoặc bản nháp/đang duyệt. Quan hệ cùng item cần
  ràng buộc FK phù hợp; trạng thái công bố còn là kiểm tra service/transaction.
- Khi có revision 1 đã công bố, giáo viên biên soạn revision 2 mà người học vẫn
  đọc revision 1. Chỉ thao tác công bố thành công mới đổi pointer sang revision 2.
  Revision 1, nguồn và tham chiếu media của nó vẫn được giữ cho lịch sử.
- Việc cập nhật pointer và ghi nhận nội dung/bản nguồn đã công bố phải atomic
  trong database. Kiểm tra version để hai thao tác công bố/archive đồng thời
  không âm thầm ghi đè nhau. version của item không được dùng thay cho số
  thứ tự revision hoặc cơ chế chống ghi đè khi sửa chính bản nháp.
- Archive item ngừng cấp vào danh sách/bài luyện mới, giữ published pointer,
  revision và tham chiếu cũ. Truy cập lịch sử vẫn phải theo quyền/trạng thái
  được cho phép; không dùng archive để xóa kết quả hoặc tự giải phóng quota media.
- created_by là người khởi tạo, không thay cho tác giả nguồn hoặc quyền sở
  hữu học liệu bên ngoài đã chốt ở D28. Quyền biên soạn vẫn thuộc CONTENT_ADMIN;
  không cấp quyền nội dung cho SYSTEM_ADMIN chỉ vì có bảng quản lý chung.
- Danh mục content_type trên phản ánh phạm vi ban đầu trong bản thiết kế cũ;
  chi tiết mỗi loại tiếp tục được rà soát ở nhóm tương ứng. Video là loại media
  theo D21, không tự thêm content_type=VIDEO hoặc tính năng chat ở bước này.

D30 đang chờ duyệt về dữ liệu content_items và nguyên tắc chọn bản công bố.
Trường, trạng thái và vòng đời content_revisions được duyệt tiếp; chưa tạo
bảng, sửa SQL đề xuất hoặc triển khai editor/công bố nội dung thực tế.
Từ yêu cầu duyệt một lượt, D30 và phần content_revisions được tổng hợp ở
B01/B02 của DATABASE_BATCH_REVIEW.md; không hỏi D30 riêng nữa.

## D01 Quan hệ nhóm và câu con đã được xác nhận

Ngày xác nhận: 25/10/2026, qua trao đổi trực tiếp với người dùng.

- Một câu hỏi thuộc tối đa một nhóm ngữ cảnh.
- Câu con của nhóm giữ nguyên quan hệ với ngữ cảnh của nhóm khi được dùng lại.
- Một nhóm có thể được sử dụng trong nhiều bộ luyện.
- Câu hỏi độc lập được phép không thuộc nhóm.

Đây là quyết định nghiệp vụ, chưa phải thay đổi SQL. Bản SQL trước đó có quan hệ
group_questions N:N nên cần được chỉnh ràng buộc sau khi các phần liên quan được
duyệt. Quyết định này chưa chốt loại bài tập, cách chấm điểm nhóm/câu con, cách
chọn loại trả lời, số câu bắt buộc hay chính sách chọn lại một phần nhóm khi luyện lỗi.
Cách gắn học liệu đã được xác nhận riêng tại D02.

## D02 Học liệu chung và riêng đã được xác nhận

Ngày xác nhận: 25/10/2026, qua trao đổi trực tiếp với người dùng.

- Ba loại học liệu đang xét: text, ảnh và audio.
- Một nhóm có thể có nhiều học liệu kết hợp, có thứ tự trình bày.
- Câu con sử dụng học liệu chung của nhóm và được bổ sung học liệu riêng khi cần.
- Câu hỏi độc lập cũng có thể có text, ảnh hoặc audio riêng.

Ví dụ: nhóm nghe có một audio chung, còn câu con số 2 có thêm một biểu đồ; nhóm
đọc có thể có hai đoạn văn và một ảnh. Đây là phạm vi gắn học liệu đã xác nhận,
chưa quyết định bảng vật lý, cách lưu phiên bản hay cơ chế kiểm soát hiển thị
transcript/lời giải. Các chính sách đó sẽ được duyệt cùng snapshot và chấm điểm.
Cách lưu/dùng lại đã được duyệt tạm tại D21; video kết hợp nhập chat là hướng
mở rộng về sau được người dùng nêu, chưa đổi phạm vi hiện tại thành có video/chat.

## Những vấn đề cần giải quyết cho luồng hiện tại

| ID | Vấn đề | Bằng chứng trong bản hiện tại | Quyết định cần chốt |
|---|---|---|---|
| DB01 | Khái niệm type chưa thống nhất | SQL dùng MULTIPLE_CHOICE/FILL_OPTION; ví dụ API dùng SINGLE_CHOICE; kiểu bài từ vựng còn có dịch hai chiều/nghe chọn | Tách mục tiêu kiến thức, cách trình bày và cách trả lời; thống nhất code FE/BE |
| DB02 | Câu trả lời đang bị đóng vào một lựa chọn hoặc text | attempt_answers có một selected_option_id hoặc answer_text; correct option tối đa một | Bản đầu hỗ trợ những dạng nào; mỗi dạng có cấu trúc đáp án/câu trả lời và cách chấm gì |
| DB03 | Nguồn tạo attempt chưa được giữ đầy đủ | attempts chỉ có practice_set_id, trong khi luồng hỗ trợ DECK, GRAMMAR_TOPIC, MIXED_GRAMMAR, MISTAKES | Lưu source type và cấu hình lựa chọn bất biến; các ID cần truy vấn có FK phù hợp |
| DB04 | Tạo attempt lặp chưa có lưu trữ idempotency | Flow yêu cầu mạng lỗi không tạo hai lượt làm; schema chưa có key/fingerprint hay bản ghi request tương ứng | Unique theo user/key, đối chiếu cùng payload và thời gian giữ kết quả request |
| DB05 | Autosave có thể nhận lựa chọn cũ đến muộn | attempt_answers có answered_at nhưng không có version/sequence; một row/snapshot không giải quyết thứ tự request | Chọn optimistic version hoặc giao thức sequence; cách bỏ đáp án và xử lý nhiều tab |
| DB06 | Snapshot nhóm và nội dung chưa có hợp đồng dữ liệu cụ thể | question_snapshot/answer_key_snapshot là JSON object; layout Part 6, group/material Part 3/4/7 chưa có schema và version thống nhất | Snapshot ID nhóm/tài liệu/blank/option, tính bất biến và khả năng render/chấm lại bản cũ |
| DB07 | Phụ thuộc media lịch sử chưa được quản lý đầy đủ | Domain FK bảo vệ media hiện hành; revision/attempt JSON có thể giữ media ID mà không có liên kết FK riêng | Giữ các reference lịch sử bằng cấu trúc tra cứu được; policy archive/xóa object |
| DB08 | Retry import từng dòng chưa có dấu vết thành công chắc chắn | import_jobs có counter, import_job_errors giữ lỗi; chưa có mapping bền vững cho dòng đã tạo entity | Một cơ chế unique(job,row)/external key hoặc nguồn import gắn vào entity trong cùng transaction |
| DB09 | Quan hệ nhóm/câu con đã chốt nghiệp vụ D01; SQL còn chờ cập nhật | group_questions hiện là N:N và chưa giới hạn mỗi question thuộc tối đa một nhóm | Một câu tối đa một nhóm; nhóm dùng lại ở nhiều bộ; câu độc lập không cần nhóm |
| DB10 | Chưa định nghĩa cách tính các chỉ số học tập | Có review timestamp và attempt duration; mục tiêu có daily_minutes | Đếm thẻ/nghĩa/lượt làm ra sao; nếu báo phút học thì đo active time hay elapsed time |

DB03/04/05 là thiếu hợp đồng hoặc cơ chế dữ liệu cho luồng đang được yêu cầu.
Chúng không nhất thiết buộc thêm một bảng cho mỗi vấn đề. Ví dụ cấu hình tạo bài
có thể được snapshot có schema, còn FK dùng cho nguồn cần tra cứu; idempotency có
thể được thiết kế ở attempt hoặc thành một cơ chế request chung nếu nhiều API cần.

## Những chính sách cần quyết định trước khi khóa thiết kế

| ID | Chính sách còn mở | Tác động |
|---|---|---|
| POL01 | Một nghĩa từ bị sửa nội dung có còn là cùng một nghĩa không | Sửa typo có thể giữ ID; đổi hẳn nghĩa nên tạo ID mới để không gán nhầm tiến độ SRS |
| POL02 | Bài Grammar cập nhật bản mới có cần học lại không | lesson_progress chỉ khóa user/lesson; nếu cần phân biệt bản đã học thì giữ revision đã hoàn thành |
| POL03 | Mistake RESOLVED theo quy tắc nào | Một lần làm đúng, N lần đúng, hay theo thời gian; ảnh hưởng counter và dữ liệu nguồn cần giữ |
| POL04 | Bộ luyện có time limit có hết hạn/tự nộp không | practice_sets có time_limit_seconds nhưng attempt chưa giữ hạn riêng; không dùng đồng hồ FE làm nguồn quyết định |
| POL05 | Publish/thu hồi một dependency ảnh hưởng nội dung cha như thế nào | Deck, group, set có thể tham chiếu item khác; phải chốt pin revision hay resolve bản hiện hành khi tạo attempt |
| POL06 | Quy tắc giữ/xóa account, session, token, reset, audit và media | Làm rõ retention, dữ liệu nào archive, dữ liệu nào được xóa vật lý và điều kiện cho phép |
| POL07 | Worker notification claim và phục hồi sau crash | Hiện chỉ PENDING/SENT/FAILED; cần transaction/lease/retry policy phù hợp, tránh giữ connection qua tác vụ gửi mạng lâu |
| POL08 | Dữ liệu được tìm kiếm/lọc và quy mô ban đầu | Chỉ tạo index theo truy vấn thực tế; không index hàng loạt JSON hoặc thêm cache/partition chỉ vì dự đoán |

## Nhóm question cần thiết kế lại theo ba lớp

### Phân loại kiến thức và mục tiêu

Category Vocabulary/Grammar/TOEIC, TOEIC Part, CEFR và điểm kiến thức là những chiều
khác nhau. Ví dụ một câu có thể là TOEIC Part 5, chủ điểm thì động từ, và trả lời
bằng cách chọn một đáp án. Các câu Part 7 còn có thể phân loại theo ý chính, chi
tiết hoặc suy luận nếu muốn báo cáo điểm yếu theo kỹ năng.

Không dùng một question_type để đồng thời đại diện mọi chiều này.

### Tương tác và cấu trúc câu trả lời

Ví dụ các cơ chế khác nhau:

| Cơ chế | Câu trả lời cần lưu |
|---|---|
| Chọn một | Một option ID |
| Chọn nhiều | Một tập option ID |
| Điền text | Text theo một hoặc nhiều blank ID |
| Ghép cặp | Các cặp ID trái/phải |
| Sắp xếp | Danh sách ID có thứ tự |

Đây là ví dụ để chốt phạm vi, không tự đưa mọi dạng vào bản đầu. Các kiểu bài
Vocabulary như Anh–Việt, Việt–Anh, nghe chọn từ có thể cùng dùng cơ chế chọn một;
chúng vẫn cần metadata để tạo bài/lọc đúng kiểu luyện.

### Đáp án và chấm điểm

Mỗi cơ chế cần chốt: đáp án chuẩn, cách kiểm tra dữ liệu gửi lên, cách tính đúng,
số điểm tối đa, điểm từng phần nếu có, và version của chính sách chấm.

Text cần quyết định hoa/thường, khoảng trắng, Unicode, dấu câu và các đáp án tương
đương nếu hỗ trợ. Chọn nhiều cần quyết định đúng toàn bộ mới có điểm hay được điểm
từng phần. Các quyết định này phải đi cùng snapshot để bài cũ không bị chấm theo
quy tắc mới.

Có thể dùng bảng catalogue question_types nếu cần metadata/nhãn dùng chung.
Nhưng thêm row catalogue không tự tạo renderer hoặc grader mới. Code và cấu trúc
câu trả lời tương ứng vẫn cần được triển khai, kiểm thử và kiểm soát version.

Tham khảo sơ đồ khái niệm tương tác, response và response processing của
[1EdTech QTI 3](https://www.imsglobal.org/spec/qti/v3p0/guide).
Đây là nguồn tham khảo thiết kế; không đề xuất buộc dự án triển khai toàn bộ QTI/XML.

## Những mở rộng không mặc định là thiếu sót của MVP

- Video ngắn kết hợp nhập chat: người dùng yêu cầu thiết kế có thể mở rộng về
  sau và đã duyệt tạm cách lưu học liệu theo D21. Chưa chốt triển khai video,
  cơ chế hội thoại, chủ thể đối thoại hoặc cách chấm trong bản đầu.
- Thêm lưu lịch sử phiên Flashcard: flow hiện tại nói rõ chưa hứa khả năng này.
- Lịch sử mục tiêu: cần nếu muốn xem người học thay đổi mục tiêu ra sao; một goal
  hiện tại/user vẫn hợp lệ nếu chỉ cần cấu hình hiện hành.
- Full TOEIC mock test: nếu thêm, bộ đề nhiều Part/section cần được mô hình hóa;
  practice_sets hiện giới hạn một Part cho TOEIC không đáp ứng ngay.
- Xác minh email, đăng nhập xã hội, MFA: chốt theo luồng account mong muốn trước
  khi thêm bảng.
- Permissions chi tiết: ba role cố định có thể đủ; không tự thêm bộ máy phân quyền động.
- Số điểm TOEIC quy đổi: không tự coi tỷ lệ đúng của bài luyện ngắn là điểm thi chuẩn.

## Quy trình duyệt lại

1. Viết danh sách kịch bản và kết quả mong đợi của mỗi nhóm nghiệp vụ.
2. Chốt phạm vi bản đầu và phần mở rộng.
3. Vẽ quan hệ dữ liệu, xác định dữ liệu hiện hành so với lịch sử/snapshot.
4. Chốt trạng thái, invariant, transaction và các tình huống cạnh tranh.
5. Liệt kê query cần phục vụ màn hình, rồi chọn index.
6. Đồng bộ DTO/API và kiểm thử nghiệm thu, sau đó mới sửa SQL/entity/migration.

Duyệt Account/Identity trước theo trình tự người dùng đã yêu cầu ở đầu tài liệu.
Chuỗi Question → Answer definition → Response → Grading → Attempt được rà soát
ở bước bài tập và câu hỏi, sau khi đã duyệt tài khoản và học liệu.

## Nguồn đã đối chiếu

- SQL hiện tại: docs/database/proposals/remaining-mvp.sql.
- Tài liệu database: docs/database/BASELINE_v3.md.
- Luồng UI: D:\MasterEnglish\docs\EnglishMaster_UIUX_User_Flows_v1_0.md.
- Quy chuẩn API v1.0: D:\MasterEnglish\docs\EnglishMaster_API_Quy_Chuan_Dac_Ta_v1_0.docx.
- Tài liệu SRS/SDD và database v2.1 là nền cũ, được giữ làm nguồn đối chiếu.

Lượt rà soát này chỉ tạo tài liệu thảo luận. Không sửa schema, entity, migration
hoặc chạy thao tác thay đổi database. Kết quả test ràng buộc trước đây không chứng
minh rằng các chính sách và luồng nghiệp vụ còn mở đã được giải quyết.
