# Nạp 6 bộ từ và phát âm lên EC2

Đây là bộ nội dung khởi đầu: 6 bộ, 59 từ, 61 nghĩa. Từ/định nghĩa/ví dụ là nội
dung mẫu tự biên soạn; bản ghi là giọng tổng hợp Piper Anh–Anh. Nên tiếp tục
thẩm định nội dung và nghe lại phát âm trước khi coi là giáo trình hoàn chỉnh.

Lệnh mới `--content-import` chạy một lần, dùng profile riêng `content-import`,
đọc cấu hình production hiện có và tự đóng ứng dụng khi hoàn tất. Listener của
tiến trình nhập bị ép về loopback/cổng ngẫu nhiên; container chạy bằng Compose
`run` không xuất cổng. API phục vụ người dùng vẫn chạy riêng. Không dùng profile
`local` trên EC2; khởi động API thông thường không tự nạp dữ liệu.

Nội dung đi qua workflow publish/validator hiện có; audio được kiểm tra MP3,
checksum, tải lên/đọc lại R2 rồi ghép với revision trên database đích. Bundle
không mang foreign key revision local sang production. Database được cập nhật
trong một transaction; lỗi sẽ rollback toàn bộ lượt nhập. R2 có thể còn object
theo checksum nếu lượt nhập bị gián đoạn; chúng có thể được tái dùng khi chạy lại.
Không xóa tài khoản, tiến độ hay lịch sử ôn. Không ghi đè bản phát âm đã có nếu
khác voice/checksum/bucket hoặc đã bị archive.

## 1. Commit và build backend mới — CMD trên Windows

Repo frontend không thay đổi ở bước này. Từ CMD:

```cmd
cd /d D:\MasterEnglish\masterenglish-api
git add -- .github/workflows/ci.yml
git add -- src/main/java/com/masterenglish/api/MasterenglishApiApplication.java src/main/java/com/masterenglish/api/ContentImportCommand.java
git add -- src/main/java/com/masterenglish/api/modules/content/pronunciation/LocalPronunciationImporter.java
git add -- src/main/java/com/masterenglish/api/modules/vocabulary/package-info.java src/main/java/com/masterenglish/api/modules/vocabulary/seed
git add -- src/main/resources/vocabulary/local-topics.json
git add -- src/test/java/com/masterenglish/api/ProductionVocabularyImportTests.java
git add -- scripts/content/grant_content_admin.sql scripts/pronunciation/package_starter.py scripts/pronunciation/test_package_starter.py
git add -- docs/vocabulary/PRODUCTION_IMPORT.md docs/vocabulary/PRONUNCIATION.md
git --no-pager diff --cached --stat
git --no-pager diff --cached --check
git commit -m "feat(vocabulary): add one-shot production starter import"
git push origin main
```

Chờ Backend CI verify/publish xanh. Lấy **Deploy by digest** của run mới, chạy
workflow Infra **Deploy production** với digest đó và chờ deploy xanh. Dùng
chính image mới này cho bước nhập, không dùng image của lần deploy trước.

## 2. Chuẩn bị file để chuyển qua WinSCP

Bundle đã đóng gói từ các MP3 hiện có tại:

`D:\MasterEnglish\masterenglish-api\build\content-import\starter-v1.zip`

SHA256 của gói đã chuẩn bị trong lượt này:

```text
1e2ee93193127180b5abc83dfd72ab48ffe6a3a27cb5613eb4848c47751422f3
```

Gói chỉ có `manifest.json` và 59 MP3, không có `.env`, model Piper hoặc database.
Không commit gói trong `build/` lên Git. Giữ bản sao gói nếu cần dọn thư mục build.

Trong SSH, tạo thư mục:

```bash
cd /srv/masterenglish
mkdir -p imports
```

Dùng WinSCP chuyển đúng 2 file:

| File trên máy Windows | Đích trên EC2 |
| --- | --- |
| `D:\MasterEnglish\masterenglish-api\build\content-import\starter-v1.zip` | `/srv/masterenglish/imports/starter-v1.zip` |
| `D:\MasterEnglish\masterenglish-api\scripts\content\grant_content_admin.sql` | `/srv/masterenglish/imports/grant_content_admin.sql` |

Nếu cần tạo lại gói, từ repo backend chạy bằng Python 3 có sẵn:

```text
python scripts/pronunciation/package_starter.py --output build/content-import/starter-v1-new.zip
```

Script kiểm checksum đầu vào và in SHA256 mới. Gói tạo lại có thể có SHA khác do
metadata ZIP; đối chiếu bằng SHA script in cho đúng file mới, không dùng SHA cũ.

## 3. Backup và kiểm tra cấu hình — SSH trên EC2

```bash
cd /srv/masterenglish
python3 scripts/backup.py run
python3 scripts/backup.py status
docker compose --env-file .env.prod -f compose.production.yaml -f compose.https.yaml config --quiet
sha256sum imports/starter-v1.zip
```

Dừng xử lý nếu backup lỗi, Compose lỗi hoặc SHA256 khác giá trị gói đã chuyển.
Giữ `R2_ENABLED=true`, `R2_ENDPOINT`, `R2_REGION`, `R2_BUCKET`,
`R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY` đúng cho bucket **media** trong
`.env.prod`. Không dùng bucket/token backup và không đưa khóa lên Pages.
Giữ nguyên các biến auth/database/mail đang chạy.

Giải nén vào thư mục mới (không nhập đè lên thư mục của một lượt khác):

```bash
python3 -m zipfile -e imports/starter-v1.zip imports/starter-v1
chmod 755 imports/starter-v1
chmod 644 imports/starter-v1/manifest.json imports/starter-v1/*.mp3
```

Các file này là nội dung học, không chứa bí mật; quyền đọc cho phép user app
10001 trong container đọc bind mount. Không thay quyền file `.env`.

## 4. Cấp CONTENT_ADMIN cho tài khoản của bạn

Bạn đã chọn cấp thêm quyền cho tài khoản hiện có. Tài khoản phải đã đăng ký,
xác minh email trên web online và ACTIVE. Thực hiện trong cùng phiên SSH:

```bash
read -r -p "Email tai khoan cua ban tren web online: " CONTENT_IMPORT_EMAIL
docker compose --env-file .env.prod -f compose.production.yaml -f compose.https.yaml exec -T --user postgres postgres psql -X -U postgres -d masterenglish -v ON_ERROR_STOP=1 -v account_email="$CONTENT_IMPORT_EMAIL" < imports/grant_content_admin.sql
```

Kết quả cần có email đúng và role `CONTENT_ADMIN`; role `LEARNER` hiện có vẫn
được giữ. SQL từ chối email không tồn tại hoặc chưa xác minh; không tạo tài khoản,
không đổi mật khẩu và không cấp SYSTEM_ADMIN. Việc cấp quyền có audit log.
Các phiên cũ của riêng tài khoản này bị thu hồi khi thêm quyền; đăng nhập lại
trên web sau đó. Chạy lại với role đã có không tăng auth_version lần nữa.

Không tiếp tục nếu email/role trả về không đúng hoặc có thông báo ERROR.

## 5. Nạp bộ từ và âm thanh — cùng phiên SSH

Lệnh sử dụng image hiện đang cấu hình trong `.env.prod`. Nó phải là bản đã deploy
ở bước 1. Không có thao tác pull/tag image mới ngầm ở bước nhập.

```bash
docker compose --env-file .env.prod -f compose.production.yaml -f compose.https.yaml run --rm --no-deps -e JAVA_TOOL_OPTIONS=-Xmx256m -e SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=2 -v /srv/masterenglish/imports/starter-v1:/import:ro api --content-import --app.vocabulary.production-import.actor-email="$CONTENT_IMPORT_EMAIL" --app.vocabulary.production-import.manifest=/import/manifest.json
```

Chờ lệnh kết thúc với exit code 0 và dòng:

```text
CONTENT_IMPORT_OK words=59 decks=6 audioImported=59
```

Nếu đã nạp đủ bằng chính gói đó trước đây, `audioImported=0` là hợp lệ và không
tạo thêm revision/bộ từ. Một lượt nhập khác đang chạy sẽ bị từ chối. Khi lỗi,
giữ log để xử lý; không bật local profile, không sửa Flyway và không chép database
local sang EC2. Có thể tái dùng gói sau khi nguyên nhân lỗi được giải quyết.

Nếu đã đóng phiên SSH hoặc biến email không còn, chạy lại dòng `read` ở bước 4
trước lệnh nhập; không cần cấp lại quyền nếu role đã có.

## 6. Xác nhận kết quả

```bash
docker compose --env-file .env.prod -f compose.production.yaml -f compose.https.yaml ps
curl --fail --silent --show-error https://apimaster.trinhdanghuy.id.vn/api/actuator/health
docker compose --env-file .env.prod -f compose.production.yaml -f compose.https.yaml exec -T --user postgres postgres psql -X -U postgres -d masterenglish -c "SELECT (SELECT count(*) FROM decks) AS decks, (SELECT count(*) FROM words) AS words, (SELECT count(*) FROM word_senses WHERE NOT is_archived) AS senses, (SELECT count(*) FROM word_pronunciations) AS pronunciations;"
```

Với database chưa có nội dung từ trước: 6 deck, 59 word, 61 sense và 59 pronunciation.
Nếu đã có nội dung khác thì tổng có thể lớn hơn; đừng xóa nội dung để ép số lượng.

Đăng nhập lại tại `https://master.trinhdanghuy.id.vn/learn/vocabulary`, tải lại trang:
6 bộ từ phải xuất hiện. Thử lật 3D, nghe thường/chậm, lưu một nghĩa rồi tải lại.
Không cần deploy lại frontend cho lượt nhập dữ liệu này.

Sau khi nhập xong và kiểm tra thành công, tạo backup mới:

```bash
python3 scripts/backup.py run
unset CONTENT_IMPORT_EMAIL
```

## Kiểm thử của công cụ

Đã chạy `spotlessApply spotlessCheck check bootJar --no-daemon` ngày 05/11/2026:
271 test đạt, 7 bỏ qua, không có failure/error. Hai test Python đóng gói cũng đạt.
Đã kiểm tra JAR nhận lệnh `--content-import` và từ chối khởi động khi thiếu tham số.
Chưa chạy nhập trên EC2 và chưa cấp quyền cho tài khoản production.

- Integration tests dùng PostgreSQL tạm: đủ bộ, remap revision, lặp không trùng,
  thiếu audio, checksum sai, đường dẫn không hợp lệ, learner bị chặn, rollback
  khi storage lỗi và cấp quyền idempotent/giữ LEARNER/từ chối chưa xác minh.
- Kiểm thử đóng gói xác nhận không mang file `.env` hay revision ID local vào ZIP.
- Test dùng storage giả, không xác nhận credential/kết nối R2 production.
  Import thực tế xác minh object qua PUT/HEAD/GET và SHA256 trước khi hoàn tất.
