# M08 — Kế hoạch sửa và nghiệm thu backend

> Thực hiện trực tiếp, từng chặng, một writer. Dùng `superpowers:executing-plans`; không subagent, không checkpoint commit.

**Mục tiêu:** nghiệm thu đủ 22 P0, AC ngang và bảo toàn AC21 theo `spec.md` §5. Không đổi chuẩn để làm xanh gate.

**Kiến trúc:** `admin` giữ HTTP, `content` giữ workflow và ledger, `practice` giữ projection; giao tiếp qua public contracts. PostgreSQL 17, Java 21, Gradle Wrapper, Testcontainers, S3 adapter local và FFmpeg runtime.

**Spec:** [`spec.md`](../../../spec.md), đặc biệt §3, §4 A–H và §5.

**Plan thực thi phần còn lại:** dùng **§8** làm thứ tự hiện hành. §1–7 giữ lịch sử; ô chưa đánh dấu trong các mục đó không tự có nghĩa chưa triển khai.

**Baseline:** `feat/m08-content-ops`, HEAD `c59c75e` cộng working tree và file untracked tại lần triển khai gần nhất. Full gate sau `CompletedMediaCleanup` exit 0, trước V21. Sau V21, 68 test tập trung pass, chưa có full gate tương ứng. Mốc «30% còn lại» là ước lượng khối lượng triển khai, không phải 30% AC. Dùng bảng quyết định tại §7 để chạy phần còn mở; §1–6 giữ chi tiết thiết kế và bằng chứng lịch sử.

**Ràng buộc:** Không sửa FE hoặc mở learner engine/attempt/grader mới. Không đọc secret, gọi R2 cloud, dùng DB chung, cài phụ thuộc, sửa migration đã áp dụng, commit/push/PR/deploy. Test với DB riêng. Mỗi lỗi nghiệp vụ có test tái hiện trước khi sửa; chỉ giữ tối ưu cần cho spec.

## Bằng chứng triển khai trên nhánh M08

- Ruling: Hoàn thiện trên `feat/m08-content-ops` trước, chưa nhập `main` theo yêu cầu. Migration trên baseline tích hợp chưa được xác nhận; không tính AC26 đạt cho baseline đó.
- Sửa compile `markRowFailed` bằng owner của job đã khóa; sửa hunk Spotless. Test `staleConfirmationCannotFailRowAfterTakeover` chứng minh token cũ bị từ chối và owner bị thu hồi `CONTENT_ADMIN` không thể ghi lỗi row hoặc finalize.
- Hồi quy Media phát hiện `TEMP` bị release tại Complete; sau sửa, Complete giữ `RESERVED` và không gọi delete. Test MinIO `putIfAbsent_keepsExistingBytes` xác nhận không ghi đè, chưa chứng minh giới hạn worker liên instance.
- Hồi quy Import phát hiện retry sau mất phản hồi PUT giữ job ở `UPLOADING`; sau sửa, retry chỉ chuyển trạng thái khi đọc lại đúng size/hash dưới quy trình kiểm quyền và khóa job. Retry khi PUT chưa đến storage tạo lại cùng key bằng PUT có điều kiện, rồi đọc lại size/hash. Hai suite Import và Spotless exit 0 sau sửa. Cleanup sau hết hạn vẫn mở.
- Gate JDK 21 `spotlessCheck test` với hai suite Import, suite AdminMedia và test MinIO `putIfAbsent_keepsExistingBytes`: 23 test, 0 fail/error/skip. Full gate trước bản sửa replay: 677 test, 1 fail (`R2MediaStorageTests.exists_returnsFalseForMissingObject`, `NoHttpResponseException`), 7 skip. Ca `exists` pass khi chạy riêng; chưa kết luận đã hết lỗi.

- Full gate sau sửa replay, trước Group guard: `spotlessCheck check bootJar` exit 0. Test Group mới tái hiện pin trỏ child đã unpublish trả 200 thay vì 400; sau sửa `ContentPublication` kiểm snapshot visible dưới khóa, suite `QuestionGroupAndPracticeSetIntegrationTests` + Spotless exit 0. Full gate sau Group guard: 75 suites, 678 test, 0 fail/error, 7 skip baseline. Sau gate này thêm kiểm quyền khi finalize Import, nên kết quả không xác nhận diff cuối. Build image local và chạy `ffmpeg -version` / `ffprobe -version` đạt với 6.1.1-3ubuntu5; chưa kiểm Java decode trong image backend.

- `CompletedMediaCleanup` dọn TEMP cho grant `COMPLETED` đã hết hạn và qua 24h. Test PostgreSQL xác nhận không xóa sớm, không nhả quota khi object còn, và không xóa lại sau release. Spotless, AdminMedia, Architecture và LayerArchitecture: 21 test, 0 fail/error/skip. Grant chưa hoàn tất, Import và fencing liên instance chưa được bao phủ.

- Full gate sau `CompletedMediaCleanup` (`bvdpyn5ll`) exit 0 trước thay đổi slot. `V21` thay semaphore JVM bằng 2 slot PostgreSQL bền; token holder mới được nhả, lease hết không tái cấp slot. Timeout/interruption của decoder đợi process được khởi chạy thoát trước khi trả slot. Gate Spotless + MediaVerification/AdminMedia/Ffmpeg/Architecture: 68 test, 0 fail/error/skip. Test PostgreSQL tranh 3 transaction và nhả sai token đã qua; chưa có bằng chứng hai tiến trình backend, mất kết nối/crash, đóng process FFmpeg thực trong image hoặc quy trình thu hồi slot crash-held. Không chấm AC27 đạt.

## 1. Khôi phục build, khóa baseline tích hợp

- [ ] `ImportJobTxService.markRowFailed(UUID jobId, UUID claimToken, int rowNumber, String errorsJson)`: lấy actor từ `ImportJob` đã khóa bởi `lockActiveClaim`, xác minh `CONTENT_ADMIN` trước khi cập nhật row. Không bỏ kiểm quyền để compile. Giữ `staleConfirmationCannotFailRowAfterTakeover`; thêm ca claim còn hiệu lực nhưng chủ job mất role, trạng thái row giữ nguyên. Soát kiểm lại role trước finalize.
- [ ] Format đúng hunk `ContentAdminServiceImpl.java:1216`. Chạy `gradlew.bat spotlessCheck compileJava compileTestJava --max-workers=1 --no-daemon`, rồi hai suite `*AdminImportIntegrationTests`, `*AdminQuestionBankImportIntegrationTests`. Cả compile, format, test phải chạy thật và pass.
- [ ] Kiểm lịch sử Flyway của baseline tích hợp: `origin/dev` có migration notification V16 và study sessions V17, trùng media receipt/import ledger local. Giữ nguyên migration đã áp dụng và V15; chỉ đánh lại số migration M08 chưa phát hành, sau toàn bộ version đã cấp, bảo toàn thứ tự phụ thuộc. Không merge vào working tree đang có thay đổi. Test fresh, upgrade V9/V13 và từ snapshot tích hợp có dữ liệu; kiểm cả trường hợp DB đã qua V17 nhưng chưa có V15. Nếu gặp V15 đến muộn, chứng minh đường nâng cấp được duyệt; không bật `outOfOrder` làm mặc định để né lỗi. Gate AC26: Flyway và Hibernate validate, không mất ID/pin/history.

## 2. Media: ghi một lần, phục hồi được — AC10/11/24/27/28

- [ ] `MediaVerificationServiceImpl`, `MediaVerificationTxService`, `MediaStorage`, `R2MediaStorage`: thay `exists` rồi `put` bằng PUT có điều kiện trên adapter; khi đã có bản cuối, chỉ dùng lại sau đọc bounded và kiểm exact size + SHA-256 bytes. Test hai writer bằng adapter S3 local và lỗi sau PUT trước commit; không overwrite, không fake `READY`, không có object thiếu allocation.
- [ ] Giữ TEMP đến sau terminal + tối thiểu 24h, URL hết hạn và không còn writer; không delete trước commit `READY`. Reconcile tệp tạm và state dở dang theo §B, giữ ledger ≥30 ngày; chỉ release khi xác nhận xóa hoặc absent và không còn khả năng ghi. Giữ final GC tắt. Test marker commit lỗi, lost response, xóa thất bại và retry cleanup; kiểm quota sau mọi bước. Kiểm tài liệu HTTP và các lỗi 401/403/404/413/409/429/503 theo spec.
- [ ] Hai slot vật lý toàn backend: thay giới hạn theo JVM bằng cơ chế giữ slot liên instance trong suốt tác vụ; lease không tự cấp lại slot nếu worker cũ còn có thể decode/ghi. Chốt cơ chế chứng minh dừng hoặc cô lập trước tái cấp; không giữ DB transaction khi I/O. Nếu chưa chứng minh ca mất kết nối/lease giữa decode, giữ AC27 `NOT VERIFIED` hoặc `FAIL`, không gọi hai lease là hai worker vật lý. Test hai instance, ba tác vụ, takeover khi decoder treo, worker cũ không được ghi hoặc commit.
- [ ] `FfmpegMediaDecoderTests`, suites Media và `Dockerfile`/CI: chạy Java qua FFmpeg đóng gói thật với runtime tái lập; test đủ JPEG/PNG/WebP/MP3/M4A AAC/ALAC, MP3 22.050 Hz, tamper/truncation/multi-frame/caps, timeout dừng process thật. Dùng MinIO local cho adapter, fake chỉ tiêm lỗi. Không đánh đồng MinIO với R2 cloud.

## 3. Import: phục hồi và kết quả theo ledger — AC14–17/24/27

- [ ] `ImportJobServiceImpl.storeInput` và `ImportJobTxService`: giữ reservation trước PUT; chỉ chuyển `UPLOADING` sau khi recheck chủ job/quyền, trạng thái dưới khóa và có bằng chứng input đúng. Retry cùng fingerprint sau lost response không trả `UPLOADING` mãi hoặc ghi đè object; mismatch trả `409`. Không giữ transaction quanh parse/PUT/read/delete. Test lost response trước/sau PUT và DB commit, hai request đồng thời, timeout/expiry và quota. Khóa/fence transition theo cùng ledger; chỉ nhả quota theo chính sách retention và bằng chứng xóa như §D/§B.
- [ ] `ImportJobTxService`/API rows: giữ kiểm token/lease cho tất cả row writes và finalize; counters khớp rows, terminal replay không tạo Draft mới. Không đưa `Exception.getMessage()` thô vào row errors/log; chỉ trả lỗi validator an toàn hoặc mã/thông báo chung. Test lỗi SQL/storage giả có chuỗi sentinel không lọt HTTP; kiểm role revoke và retry terminal. Giữ test JSON group child cuối lỗi rollback, CSV có dòng lỗi giữa hai dòng tốt, cap 5 MiB qua multipart embedded và 5.000/5.001 record, DryRun zero content writes qua commit. Điều chỉnh docs cùng với hành vi thật.

## 4. Publication, pin và lịch sử — AC05–09/12/13/16/20/22/23

- [ ] `ContentPublication.recordQuestionGroupDependencies` và `QuestionGroupProjectionListener`: branch pin tường minh phải kiểm visibility/eligibility như branch tìm revision, không chỉ type/owner/status. Chuẩn hóa pin ngay lúc publish nếu đầu vào cho phép bỏ pin; snapshot, dependency và readback nhất quán. Test child archived/revoked với pin đúng, child v2 không đổi pin Group đã publish, rollback child cuối và race archive/publish. Chạy đủ Part 3/4/6/7.
- [ ] `ContentPublication.validateFixedSetEntries` và public `practice` contract: kiểm membership question dưới khóa khi publish; cấm grouped child làm entry lẻ. Giữ order, exact pin, đếm children từ snapshot đã pin, 1–100, category/Part; kiểm config DYNAMIC version 1 và allowlist. Test group assignment cạnh tranh với publish Set, group pin cũ sau v2, rollback và không tạo learner routes.
- [ ] `ContentPublication` và public Vocabulary contract: resolve đúng `word_sense_id` và Word Published owner dưới khóa; không dùng mỗi JSON probe làm bằng chứng ownership và visibility. Test sense archive-vs-publish, pin giữ qua v2; Grammar topic pin/distinct, filter không lấy TOEIC hoặc grouped child.
- [ ] `PronunciationServiceImpl`, `PronunciationOperations`, `ContentEvents` và caller trả card: lấy audio đúng revision/sense, kiểm entitlement trước cấp URL và giữ wire learner cũ. Fixture hai sense cùng revision có hai audio; assert lịch sử sau publish v2/unpublish, revoke không cấp URL, legacy fallback không sang revision khác. Soát media archive theo §C và grammar lesson body v1: không thêm block trái schema; nếu hợp đồng media lesson còn xung đột thì ghi AC12 chưa đạt, không nới AC.

## 5. Gate độc lập và quyết định nghiệm thu — AC01–AC29

- [ ] Chạy hồi quy Word/Deck/sense/SRS, Grammar, source, lifecycle, preview, CAS, history, security, architecture, Group/Set và import/media. Không lấy test mới thay cho phần cũ. Kiểm `docs/admin/CONTRACT.md` và docs domain phản ánh response/status/limits thật.
- [ ] Chạy trên diff cuối: `gradlew.bat spotlessCheck check bootJar --max-workers=1 --no-daemon`, `git diff --check`, `docker build .` và runtime decoder; chạy pronunciation Python tests nếu sửa scripts. Ghi HEAD, phạm vi diff/untracked, exit, XML suites/tests/failures/errors/skips; đối chiếu đúng bảy skip baseline nếu xuất hiện. Không dùng `UP-TO-DATE`, mock-only hoặc báo cáo cũ thay fresh proof nơi spec yêu cầu.
- [ ] Ma trận 29 dòng: `AC | PASS/FAIL/NOT VERIFIED | hành vi | file:line/test | lệnh/kết quả | gap`; chấm từng AC theo `spec.md:123–154`. Tỷ lệ nghiệm thu = số AC PASS/29, báo riêng số P0 PASS/22. Chỉ ghi M08 hoàn thành khi mọi P0 và AC ngang đều PASS, AC21 được bảo toàn, không còn blocker. R2 cloud/FE E2E/production ghi riêng `NOT VERIFIED`; không biến thành kết quả local hay tuyên bố deploy.

## 6. Trình tự thực hiện phần còn lại

Mỗi chặng: chạy ca lỗi trước khi sửa, sửa tối thiểu, chạy lại ca đó và suite liên quan. Ghi lệnh, exit, XML, invariant kiểm được và phần chưa kiểm ngay sau mỗi chặng. Dùng JDK 21, PostgreSQL 17 local, một Gradle writer. Không chạy lại job bị hệ thống dừng vì thiếu RAM khi chưa được yêu cầu. Không cần làm lại phần đã có bằng chứng còn khớp diff.

### 6.0. Chốt snapshot làm việc

- [ ] Ghi `git rev-parse HEAD`, `git status --short --branch`, `git diff --check`, danh sách migration/test untracked. Đọc exit log của `bvdpyn5ll` nếu còn; log thiếu kết quả cuối không tính PASS. Chạy `spotlessCheck compileTestJava` trước nếu gate bị gián đoạn. Khởi tạo ma trận tại `docs/superpowers/plans/m08-acceptance-evidence.md`; mỗi AC mặc định `NOT VERIFIED` cho snapshot mới.

### 6.1. Chặn worker cũ trước khi mở rộng cleanup — AC10/11/27

- [ ] Test `MediaVerificationIntegrationTests`: hai backend dùng chung DB, worker 1 giữ decoder bằng latch, worker 2 giữ slot còn lại. Cho lease worker 1 hết hạn, ngắt kết nối DB của worker 1; worker 3 vẫn không được decode. Nhả worker 1 và xác minh process con đã thoát; worker 3 mới được chạy. Assert đỉnh đồng thời ≤2 và token cũ không thể commit. Dùng latch/timeout có giới hạn, không `sleep` để suy cạnh tranh.
- [ ] Sửa `MediaVerificationServiceImpl`/`MediaVerificationTxService`: giữ đúng hai slot bền trong PostgreSQL, cấp slot nguyên tử trong transaction ngắn, phân biệt slot vật lý với lease nghiệp vụ. Worker chỉ trả slot khi decoder và I/O đã dừng; release phải match worker/attempt token. Mất kết nối hoặc lease không tự nhả slot. Slot kẹt do crash chỉ được thu hồi khi chứng minh worker cũ và process con đã dừng hoặc bị cô lập, đồng thời xử lý PUT đang có kết quả bất định. Advisory lock theo session, timeout hay heartbeat riêng lẻ không đủ. Nếu môi trường không chứng minh được điều kiện thu hồi, giữ slot, ghi blocker AC27; không tự động takeover chỉ để lấy lại thông lượng.
- [ ] Test adapter MinIO và luồng finalize: hai PUT cùng key, lỗi sau PUT trước DB commit, bytes sai cùng kích thước, bytes dư một byte. Bản cuối không đổi; chỉ `READY` sau exact size/hash và commit; bản cuối có thể tồn tại luôn có allocation. Bổ sung ca retry gặp bản cuối từ lần trước rồi decode thất bại: không release CONTENT nếu chưa loại trừ final object. Kiểm tra chiều khóa pool → grant → allocation; không giữ DB tx trong decode/storage.
- [ ] Gate: `test --tests '*MediaVerificationIntegrationTests' --tests '*R2MediaStorageTests' --tests '*AdminMediaIntegrationTests'`. Lặp cả suite nếu test MinIO lỗi vận chuyển; không coi lỗi vận chuyển là object không tồn tại.

### 6.2. Cleanup Media và Import theo ledger — AC10/11/14/15/17/26/27

- [ ] Mở rộng từ `CompletedMediaCleanup.runAt(Instant now)` và query `MediaUploadGrantRepository.findCompletedForCleanup(...)`. Trước hết khóa/chốt request hết hạn không còn quyền ghi: `PENDING` → `EXPIRED`; `VERIFYING` quá deadline → `FAILED` khi worker đã dừng; cập nhật asset nhất quán với constraint V15. Không chạm terminal metadata. Staging/Import gắn quyền ghi với attempt cụ thể trước storage I/O; chuyển terminal không được coi là chứng minh PUT đã dừng. Tách chọn ứng viên, I/O và ghi nhận bằng chứng; dùng thời gian DB sau khóa cho transition.
- [ ] TEMP Media/Import: xóa sớm nhất tại terminal + 24 giờ, sau hạn upload và sau khi chặn mọi writer. Job Import `UPLOADING` quá hạn không còn ghi được phải chốt `EXPIRED`; `READY` không tự hết hạn theo hạn PUT nếu hợp đồng vẫn cho confirm. Dùng đúng bucket/key đã ghi ledger và đối chiếu với adapter; không biến bucket giả `temp` hoặc bucket không khớp thành bằng chứng absent. Giữ ledger/metadata ≥30 ngày; tắt GC final. Nếu final tồn tại, không nhả CONTENT vì worker fail hoặc job hết hạn.
- [ ] Test PostgreSQL + MinIO: `23h59m59s` giữ quota, `24h` eligible; bucket không khớp hoặc timeout giữ quota; object absent chỉ release khi không còn writer; crash sau delete trước commit chạy lại không trừ quota hai lần; cleanup song song không đụng final key; 50 ứng viên lỗi đầu không làm ứng viên sau đó chờ vô hạn. Ca worker bị treo nhả quyền chỉ sau tín hiệu dừng đã kiểm ở 6.1, không dựa vào TTL. Gate `*AdminMediaIntegrationTests`, `*MediaStagingIntegrationTests`, `*MediaDatabaseMigrationTests`, hai suite `*Admin*ImportIntegrationTests` và `*ArchitectureTests`.

### 6.3. Chốt Import và publication theo hợp đồng — AC05–09/14–17/20/22–24

- [ ] `ImportJobServiceImpl`/`ImportJobTxService`: initial PUT, replay và finish đều bảo toàn reservation và cùng bằng chứng input đúng; finalize đếm từ trạng thái row bền dưới claim. Bỏ raw `getMessage()` khỏi đường lỗi SQL/storage; giữ lỗi field từ validator có kiểm soát. Test sentinel không xuất hiện trong API/log, replay cả terminal `FAILED` đã confirm, request cùng key khác fingerprint `409`, role đổi giữa claim/row/finalize, 5 MiB thực và 5.000/5.001 records, DryRun không tạo content, rollback group khi child cuối sai. Không biến lỗi quyền thành row `FAILED` nghiệp vụ. Gate hai suite Import và parser liên quan.
- [ ] `ContentPublication`, `QuestionGroupProjectionListener`, `PracticeSetProjectionListener`: lưu revision đã resolve khi Group không gửi pin, đối chiếu published snapshot/dependency/projection và giữ pin qua v2; dùng public `practice` contract để từ chối FIXED entry là grouped child dưới khóa cùng trật tự với publish Group. Test cạnh tranh theo cả hai thứ tự, đủ Part 3/4/6/7, group thiếu/khác category hoặc Part, FIXED tổng 100/101 câu, DYNAMIC cấm mixed filter và giữ version hợp lệ. Không thêm learner engine. Gate `*QuestionGroupAndPracticeSetIntegrationTests`, `*QuestionAuthoringIntegrationTests`, `*PracticeIntegrationTests`.
- [ ] `ContentPublication` và public contract Vocabulary: resolve đúng sense đang visible, pin Word revision dưới khóa, kiểm hai thứ tự publish/archive; bảo toàn Grammar topic pin và distinct. Chạy các test cạnh tranh bằng transaction thật; đối chiếu PID waiter/holder theo spec. Gate `*QuestionAuthoringIntegrationTests`, `*GrammarAuthoringIntegrationTests`, `*ArchitectureTests`.

### 6.4. Audio theo sense và runtime đóng gói — AC10/11/12/20/28

- [ ] Test `PronunciationIntegrationTests`/`WordMediaIntegrationTests`: Word revision v1 có hai sense dùng hai object khác nhau; card mỗi sense phát đúng object; sense không thuộc revision bị từ chối. Sau v2, lịch sử còn quyền vẫn phát v1; unpublish không tự cấp quyền lịch sử mới; revoke chặn cả ticket và playback. Ca legacy chỉ fallback cùng revision, không lấy audio sense khác.
- [ ] Thêm `ContentEvents.audioUrl(UUID wordId, UUID revisionId, UUID senseId)` và listener tương ứng; giữ overload hai tham số cho caller cấp Word. `PronunciationOperations` lấy `audio_media_id` của đúng sense trong snapshot pin, chỉ dùng asset đã bind vào revision và chưa bị xóa. `VocabularyQueryServiceImpl` truyền sense ID từ card; giữ shape response. Nếu route hiện tại cần định danh sense để phân giải ticket, bổ sung tham số tương thích ngược và kiểm entitlement từ principal/tài liệu lịch sử, không tin ID từ client. Nếu learner không có cách chứng minh entitlement hoặc Grammar lesson v1 chưa có schema media được duyệt, ghi AC12 gap cụ thể; không sửa schema ngoài hợp đồng.
- [ ] Build từ JAR cuối bằng `docker build .`; chạy test Java decoder trỏ vào chính runtime backend đã build, không dùng image prototype làm bằng chứng. Probe phiên bản/pin, đủ JPEG/PNG/WebP/MP3/M4A AAC/ALAC; kiểm timeout bằng việc process FFmpeg thật đã thoát. Ghi image ID, phiên bản FFmpeg/ffprobe và lệnh test. API integration dùng adapter MinIO local; R2 cloud vẫn ghi riêng.

### 6.5. Migration trên baseline mục tiêu — AC26

- [ ] Giữ nhánh hiện tại; không merge/pull trong chặng này. Lập bảng version/checksum từ V9, V13 và baseline tích hợp đã có notification/study sessions. Chỉ đổi số migration M08 xác nhận chưa áp dụng; phải kiểm thứ tự media receipt → import → practice và không bỏ V15 khi baseline đã qua V17. Migration đã áp dụng không sửa, không `repair`, không `outOfOrder` mặc định. Dùng fixture PostgreSQL local mô phỏng baseline; nếu thiếu lịch sử thật của target, không xác nhận upgrade target đã đạt.
- [ ] `MediaDatabaseMigrationTests` và test upgrade liên quan: fresh tới latest; V9 có Word/Deck/SRS; V13 có Grammar/Practice; baseline tích hợp có notification/study sessions và dữ liệu. Trước/sau so ID, pin, refs, quyền lịch sử, pool counter; Flyway validate và Hibernate `ddl-auto=validate`. Chạy riêng từng đường nâng cấp và ghi version thực thi.

### 6.6. Nghiệm thu độc lập, không đếm test thay AC

- [ ] Ma trận đủ 29 dòng: AC01–04 dùng Word/Deck/SRS/Grammar; AC05–09 dùng Question/Group/Set; AC10–12 dùng Media/runtime/playback; AC13 và AC16/18–21 dùng source/preview/lifecycle/CAS/history; AC14–15/17 dùng hai luồng Import; AC22–23 dùng link Word/topic và learner selection; AC24–29 dùng HTTP privacy, architecture, migration, fault/race, gate và docs/handoff. Mỗi dòng gắn assertion cụ thể và kết quả trên diff cuối; checklist này không tự cấp PASS.
- [ ] Chạy tuần tự `git diff --check`, `.\gradlew.bat spotlessCheck check bootJar --max-workers=1 --no-daemon`, image/runtime ở 6.4. Nếu full gate không thực thi `test`, chạy lại với `--rerun-tasks` khi tài nguyên cho phép. Ghi 7 skip baseline nếu đúng `ProposedDatabaseSchemaTests`; skip mới hoặc gate bị dừng không tính đạt. Không gọi R2/cloud, DB chung hoặc deploy. Chốt **22/22 P0**, AC21 được bảo toàn và toàn bộ AC ngang PASS; nếu chưa đủ, ghi từng blocker và bằng chứng cần bổ sung.

## 7. Checklist đóng phần còn lại

Ký hiệu: **đã có** là hành vi có test tập trung; **còn mở** là việc cần làm hoặc cần chứng minh trước nghiệm thu. Thực hiện trực tiếp trên nhánh M08, một writer, không nhập `main` trong lượt implementation. Dùng `spec.md` nếu checklist và spec khác nhau.

| Thứ tự | Đã có, không viết lại | Còn mở và điều kiện chuyển chặng |
|---|---|---|
| T0 — Baseline | Hoàn tất baseline local: HEAD `c59c75e`, `spotlessCheck compileTestJava` exit 0 với JDK 21, ma trận 29 AC đã tạo. Ref `origin/dev` local trùng V16/V17. | Kiểm đường upgrade trên baseline tích hợp ở T6; chưa nhập `main`/`dev`. |
| T1 — Media worker và final | Hai slot PostgreSQL theo token; PUT có điều kiện; readback size/hash. Ledger lưu bucket/key trước PUT; mất phản hồi rồi retry decode lỗi vẫn giữ CONTENT `RESERVED`. Gate Media: 39 test đạt. | Kiểm hai tiến trình cùng DB, mất lease/mất kết nối, crash và ca MinIO tiêm lỗi DB sau PUT rồi retry thành công. Chỉ thu hồi slot khi có bằng chứng worker và process con dừng. |
| T2 — Cleanup | `CompletedMediaCleanup` cho Media `COMPLETED`, đủ 24h và hết hạn. | Bổ sung Media `PENDING` quá hạn/`VERIFYING` quá deadline và Import đã kết thúc hoặc quá hạn upload. Chứng minh không còn writer, dùng đúng bucket, xác nhận xóa rồi mới release. Không dọn final hoặc xóa lịch sử sớm. |
| T3 — Import | Ledger trước PUT, retry PUT thiếu/mất phản hồi, claim/role check. Initial PUT đọc lại kích thước và SHA-256 trước `READY`; gate Import 16 test đạt. | Counter dựa vào ledger; replay terminal `FAILED`; lỗi private không lọt HTTP/log; đủ cap, DryRun và atomic group. |
| T4 — Publish | Group + child APPROVED chung transaction; pin explicit bị từ chối nếu child đã unpublish; Set có FIXED/DYNAMIC. Đã thêm assertion pin Group qua child v2, chưa chạy. | Chạy test pin trước khi sửa code Group; sau đó kiểm FIXED loại grouped child dưới khóa, pin sense Word qua public contract, race, Part, giới hạn 100/101 và history. |
| T5 — Audio và decoder | Word-level playback; ảnh/audio decoder tests; image CLI smoke. | Audio đúng sense và entitlement; Java decode chạy với FFmpeg trong chính image backend; đủ format và process-exit proof. |
| T6 — Migration | Migration local tới V21. | Fresh, upgrade V9/V13 và baseline tích hợp. Chỉ đổi số migration M08 chưa áp dụng; không thay V15/checksum đã áp dụng. Nếu target đã qua V15 mà thiếu V15, cần đường nâng cấp được duyệt và test local. |
| T7 — Nghiệm thu | Đã tạo ma trận 29 AC. Full gate JDK 21 exit 0 sau sửa Media/Import, trước khi thêm assertion Group. | Chạy lại full gate và runtime trên diff cuối; hoàn thiện chứng cứ từng AC. Chưa nghiệm thu M08. |

### Nhật ký triển khai

- T0: HEAD `c59c75e`, nhánh `feat/m08-content-ops`, giữ nguyên diff. `git diff --check` và `spotlessCheck compileTestJava --max-workers=1 --no-daemon` exit 0 với JDK 21. Ref `origin/dev` local trùng V16/V17; chưa nhập baseline. [Ma trận AC01–AC29](m08-acceptance-evidence.md).
- T1: regression PUT final mất phản hồi rồi retry decode lỗi tái hiện `FAILED` sai; ghi bucket/key vào allocation RESERVED trước PUT giúp giữ quota và trạng thái retry. Gate 39 test Media exit 0. Chưa đóng T1: storage fault fixture dùng mock, chưa có worker đa tiến trình.
- Ruling: không mở T2 cho writer chưa chứng minh đã dừng. Kiểm trước luồng đọc lại input của T3 vì không xóa object hoặc nhả quota. Vẫn cần test tích hợp T2/T3 trước khi chấm AC27.
- T3: initial PUT phải đọc lại đúng kích thước và SHA-256 trước `READY`. Test sai bytes cùng kích thước: HTTP 201 trước sửa; HTTP 503 sau sửa, job vẫn `UPLOADING` và allocation vẫn `RESERVED`. Hai suite Import: 16 test, 0 fail/error/skip.

### Điểm tiếp tục cho phiên sau

**Cập nhật 2026-11-27:** các bước dưới đây là handoff lịch sử, không phải danh sách việc chưa chạy hiện tại. Gate ngày 2026-11-26 ghi nhận 693 tests, 0 fail/error, 7 skip baseline. Người dùng xác nhận M08 chưa áp dụng DB dùng chung; V15 chưa có trong main local. Đổi version M08 V15–V21 thành V18–V24, giữ SQL và thứ tự, để tránh V16/V17 của origin/dev và V15 đến muộn. Fixture V11/V16/V17 từ origin/dev f1fc003 chỉ nằm trong test resources; không merge nhánh. Test lease expiry cũ chưa ngắt connection thật; proof nhiều JVM mới trong MediaVerificationProcessTests đã qua, nhưng không thay proof FFmpeg/storage toàn luồng. AC26/AC27 chỉ đóng khi đủ bằng chứng; xem ma trận nghiệm thu và ledger.

1. Giữ nguyên diff trên `feat/m08-content-ops`, kể cả tệp untracked. Chỉ một tiến trình Gradle ghi kết quả tại một thời điểm. Không commit, push, tạo PR, deploy hoặc nhập `main`/`dev`. Không đọc secret, gọi R2 cloud, áp migration DB dùng chung, cài tool hệ thống hoặc thêm dependency chưa được duyệt.
2. Chạy `.\gradlew.bat test --tests '*QuestionGroupAndPracticeSetIntegrationTests.toeicPart3QuestionGroup_publish_successAndProjections' --max-workers=1 --no-daemon` với JDK 21 và Docker local. Test đã thêm assertion rằng snapshot Group ghi đúng pin, dependency khớp và pin không đổi sau khi publish child v2. Chưa có kết quả RED/GREEN, chưa sửa production cho T4. Xác định lỗi từ kết quả test rồi mới sửa.
3. Tiếp tục các mục mở bên dưới. T2 vẫn phụ thuộc bằng chứng writer đã dừng từ T1; không dùng TTL để nhả quota. Full gate trước assertion Group chỉ là bằng chứng trung gian; chạy lại trên diff cuối trước khi chấm AC.

### T1/T2 — Proof bắt buộc trước khi release dung lượng

- [ ] Trong `MediaVerificationIntegrationTests`, thêm `physicalSlotSurvivesConnectionLossAndLeaseExpiry`: chạy hai backend test cùng PostgreSQL, latch giữ hai worker, hết lease/mất connection một worker, thứ ba không vào decoder; worker cũ không commit sau takeover. Ghi PID/process identity đã xác minh trước khi thu hồi crash-held slot. Nếu không có cơ chế xác minh dừng/cô lập, để slot bị giữ và đánh AC27 chưa đạt. Không dùng TTL để làm bằng chứng dừng.
- [x] `MediaVerificationIntegrationTests.failedRetryKeepsContentAllocationWhenFinalMayExist`: PostgreSQL + storage giả ghi bytes rồi mất phản hồi PUT; lần retry decode lỗi giữ `VERIFYING` và CONTENT `RESERVED`. Gate Media exit 0.
- [ ] Bổ sung ca MinIO thật: lưu final, tiêm lỗi DB trước `READY`, lần retry decode hoặc read lỗi. Assert final bytes không đổi, asset không `READY` giả, CONTENT còn allocation; retry hợp lệ sau đó mới chuyển `COMMITTED`. Chạy `*MediaVerificationIntegrationTests`, `*R2MediaStorageTests`, `*AdminMediaIntegrationTests`.
- [ ] Mở rộng `CompletedMediaCleanup.runAt(Instant now)` sau khi có quy tắc fencing cho từng loại writer. Test `cleanupRetainsReservationUntilPhysicalAbsenceIsProven`: tại `23h59m59s` không xóa, tại `24h` chỉ xử lý terminal đã hết hạn; bucket sai, timeout và writer chưa dừng đều giữ quota. Tiêm lỗi sau delete trước ghi DB, chạy lại hai lần, assert đúng một lần giảm quota và không đụng final key/refs. Kiểm thêm công bằng khi batch đầu bị lỗi, và metadata còn sau mốc cleanup. Chạy `*AdminMediaIntegrationTests`, `*MediaStagingIntegrationTests`, `*MediaDatabaseMigrationTests`, hai suite Import.

### T3/T4 — Proof nghiệp vụ trên DB thật

- [x] `AdminImportIntegrationTests.initialPutDoesNotReadyJobWhenReadBackDiffers`: initial PUT chưa được xác minh trả HTTP 503, giữ `UPLOADING` và `RESERVED`. Hai suite Import exit 0.
- [ ] `AdminImportIntegrationTests`: `failedTerminalReplayDoesNotCreateNewDrafts`, `rowErrorsDoNotExposeStorageOrSqlMessage`. Tiêm đúng cổng storage/DB, assert status, ledger, row counters và không lộ sentinel. Giữ test role revoke, fingerprint `409`, retry và quota; thêm CSV 5.000/5.001, multipart cap 5 MiB trên server thật. `AdminQuestionBankImportIntegrationTests` giữ one-row Group và rollback child cuối. Chỉ dùng lỗi field đã được định nghĩa để trả client.
- [ ] `QuestionGroupAndPracticeSetIntegrationTests`: `groupSnapshotKeepsResolvedChildRevisionAfterV2`, `fixedSetRejectsGroupedChild`, `fixedSetCountsPinnedGroupChildrenAtLimit`. Assert snapshot pin và `revision_dependencies` trùng nhau; 100 câu hợp lệ, 101 từ chối; không tạo projection khi lỗi. Dùng hai transaction với latch và `pg_blocking_pids` để kiểm cả hai thứ tự Group/Set. `QuestionAuthoringIntegrationTests` kiểm `question_words` đúng sense/pin và `question_topics` distinct, bài Grammar không lấy TOEIC/grouped child. Chạy thêm `*PracticeIntegrationTests`, `*GrammarAuthoringIntegrationTests`, `*ArchitectureTests`.

### T5/T6/T7 — Cổng chốt nghiệm thu (lịch sử; phần còn lại tại §8)

- [ ] Audio: fixture một Word có hai sense với object khác nhau. Kiểm URL của card, ticket và playback cùng revision/sense, quyền lịch sử v1 sau v2, unpublish không cấp quyền mới, revoke chặn đọc. Giữ overload Word-level và shape DTO. Nếu lesson v1 chưa có media schema hợp lệ hoặc thiếu nguồn xác thực quyền lịch sử, ghi đúng phần AC12 còn thiếu, không đánh PASS.
- [ ] Runtime: build từ JAR cuối; ghi image ID và FFmpeg/ffprobe version. Dùng Java decoder test đủ JPEG/PNG/WebP/MP3/M4A AAC/ALAC trong runtime chính; timeout phải chứng minh process FFmpeg thật đã thoát. Không dùng phiên bản binary hoặc CLI smoke thay Java gate.
- [ ] Migration: lưu bảng `baseline SHA | version trước | migration đã áp dụng | version sau | ID/pin/history trước và sau`. PostgreSQL 17 riêng cho từng đường fresh/V9/V13/integration; verify Flyway và Hibernate. Không mở DB chung, không `repair`, không bỏ qua migration còn thiếu. Nếu chưa được phép nhập `main`, kết quả chỉ là fixture tích hợp local.
- [ ] Tạo `docs/superpowers/plans/m08-acceptance-evidence.md` với 29 dòng: `AC | PASS/FAIL/NOT VERIFIED | test/assertion | file:line | lệnh/exit/XML | blocker`. Mỗi PASS phải có assertion đủ điều kiện §5 trên diff cuối. Bổ sung nghiệm thu hồi quy Word/Deck/Grammar, source, preview, submit/approve/reject, publish/unpublish, clone/CAS, security và architecture dù không có code mới.
- [ ] Chạy độc lập `.\gradlew.bat spotlessCheck check bootJar --max-workers=1 --no-daemon` và `git diff --check`; ghi kết quả JDK 21, suite/test/fail/error/skip, đúng các skip baseline. Nếu bị dừng hoặc lệch diff, `NOT VERIFIED`, không lấy log cũ thay. Cập nhật contract và handoff; tách `branch local`, `main`, `R2 cloud`, `FE E2E`, `production`. Chỉ chốt **M08 đạt** khi đủ 22 P0, các AC ngang và AC21 được bảo toàn.

## 8. Plan đóng MVP M08 — cập nhật 2026-11-27

### Trạng thái đầu vào và phạm vi

- Snapshot: nhánh `feat/m08-content-ops`, HEAD `c59c75e3adb63816c0b467bcc5ac298f36527f29` cộng toàn bộ diff/untracked hiện có. Không reset, clean hoặc loại bỏ thay đổi của phiên trước.
- Gate mới nhất đã chạy: `spotlessCheck check bootJar --max-workers=1 --no-daemon`, exit 0; **78 suites, 697 tests, 0 failures/errors, 7 baseline skips** tại `ProposedDatabaseSchemaTests`. `git diff --check` đạt. Đây chưa phải bằng chứng image/runtime cuối.
- Migration M08 hiện là V18–V24, SQL giữ nguyên. Người dùng đã xác nhận chưa áp dụng M08 trên DB dùng chung. Fresh, V9/V13 và fixture V17 đã chạy trong gate; không đổi số lần nữa nếu không phát hiện xung đột mới.
- Không xây hệ thống điều phối hoặc tự phục hồi phân tán mới chỉ để đóng AC. Giữ fail-closed: crash không tự nhả slot. An toàn phải được chứng minh; tự phục hồi thông lượng không phải điều kiện tự đặt thêm.
- Một writer; không subagent, commit/push/PR/merge/deploy, R2 cloud, DB chung, secret hoặc dependency mới. Lập plan không cấp quyền chạy các thao tác đó. Mỗi chặng ghi kết quả vào ledger và ma trận; tiếp tục chặng độc lập nếu bị chặn.

### Phase 0 — Chốt hợp đồng và bộ bằng chứng được dùng

**Đọc trước khi sửa:** `spec.md` §3, §4 B/H và §5; §8 này; `docs/database/MIGRATION_PLAN.md:22–40`; `docs/admin/CONTRACT.md`; ma trận nghiệm thu và ledger hiện có.

**API/pattern đã xác minh, không tự phát minh:**
- `MediaVerificationTxService.tryAcquirePhysicalSlot(UUID)` và `releasePhysicalSlot(UUID)`, `MediaUploadGrantRepository.acquirePhysicalSlot(UUID)` / `releasePhysicalSlot(UUID)`: dùng token và SQL production hiện tại; không viết cơ chế slot riêng cho test.
- `MediaVerificationServiceImpl.verifyAndFinalize(...)`: slot bao quanh decode/storage/finalize và release ở finally; kiểm lại thân hàm trước sửa.
- `FfmpegMediaDecoder` constructor nhận binary paths, `List<String> commandPrefix`, `Function<Path,String> pathTranslator`, `Duration processTimeout`, `ObjectMapper` (source tại `FfmpegMediaDecoder.java:73`). Tái dùng seam này thay thêm endpoint/debug hook production.
- `FfmpegMediaDecoderTests.java:32–58` dùng container FFmpeg dựng riêng; **không phải image backend cuối**. Test timeout tại `:450–495` dùng Java `SlowProbe`; **không chứng minh FFmpeg thật hoặc process trong container đã thoát**.
- `MediaVerificationProcessTests.java:23` chứng minh SQL/slot với hai JVM JDBC; **không phải hai backend có decoder/storage thật**.
- `MediaDatabaseMigrationTests.java:30,75,792` chứa kiểm version, upgrade fixture V17 và backfill V9/V13. Fixture `src/test/resources/db/m08-integration-baseline/` khớp `origin/dev` local SHA `f1fc00330b76d30e7f1eb6b4011027bb52b27fe7`.

**Checklist:** ghi snapshot và các assertion còn thiếu; đọc package/module boundaries trước import xuyên module. Không coi tên test, số test hoặc tỷ lệ lịch sử 27/29 là bằng chứng đủ. Không cần đọc lại toàn repo.

### Phase 1 — Chốt AC26 bằng bằng chứng hiện có

**Việc làm:**
1. Đối chiếu XML/log gate mới nhất với ba đường fresh, V9/V13 và fixture tích hợp V17; ghi baseline/version trước/sau, checksum/script, ID/revision/ref trước/sau và counter quota.
2. Kiểm riêng assertion giữ pin/history/entitlement và dữ liệu Word/Deck/SRS/Grammar theo yêu cầu AC26. Nếu thiếu, mở rộng fixture/test hiện có ở đúng tầng; không chấm PASS bằng assertion chỉ đếm bảng. Không sửa production nếu chỉ thiếu bằng chứng.
3. Chứng minh Hibernate validate trên schema sau upgrade fixture, không suy từ Spring context fresh. Tái dùng cấu hình persistence/test hiện có; không thêm entity scan hoặc cấu hình production dành riêng cho test. Nếu chưa làm được, ghi chính xác phần này NOT VERIFIED.
4. Cập nhật AC26 theo bằng chứng local; giữ deployment target thật ngoài phạm vi.

**Verify:** chạy `test --tests '*MediaDatabaseMigrationTests'` và test upgrade/validate bổ sung nếu có, JDK21, PostgreSQL17 riêng. Giữ fixture V11/V16/V17 và mọi migration baseline đã áp dụng nguyên vẹn. Không `repair`, `baseline`, mặc định `outOfOrder` hoặc thêm migration notification vào production của nhánh M08.

**Điều kiện đóng:** fresh/upgrade đúng yêu cầu, Flyway và Hibernate validate, không mất ID/history/refs, có assertion cụ thể và lệnh/exit/XML. Không yêu cầu merge `origin/dev`.

**Tiến độ 2026-11-27:** ĐÃ ĐÓNG (local): fresh V1..V24, upgrade V5/V8.1 (`VocabularyUpgradeTests` 4/4 PASS), V9/V13 (`MediaDatabaseMigrationTests` parameterized PASS) và fixture tích hợp V17→V24 (`MediaDatabaseMigrationTests` 12/12 PASS trong full gate 79 suites/704 tests) có Hibernate `ddl-auto=validate` trực tiếp trên DB nâng cấp, giữ nguyên checksum/script và dữ liệu Word/SRS/review/notification; `spring.flyway.out-of-order=false` được xác nhận. Shared deployment DB ngoài local vẫn `NOT VERIFIED`.

### Phase 2 — Dùng image backend cuối cho Java decoder

**Việc làm:**
1. Build JAR rồi image bằng `Dockerfile` gốc. Ghi JAR SHA-256, image ID/digest, Java và FFmpeg/ffprobe versions; không dùng image prototype `masterenglish-ffmpeg-runtime:test` làm chứng cứ cuối.
2. Tái dùng `FfmpegMediaDecoderTests` và constructor ở Phase 0 để chạy cùng fixture/validator qua binary trong image backend vừa build, đúng user non-root. Chỉ thêm lựa chọn image test khi cần; không thêm dependency hoặc thay decoder bằng CLI smoke.
3. Bao phủ JPEG/PNG/WebP, MP3, M4A AAC/ALAC, MP3 22.050 Hz, corrupt/truncated/multi-frame/limit. Đối chiếu test hiện có để không viết lại.
4. Bổ sung timeout/interruption với FFmpeg thật. Đồng bộ bằng tín hiệu process đã bắt đầu và PID/process identity trong đúng container; chờ exit có giới hạn. Việc `docker exec` client kết thúc không tự chứng minh process FFmpeg trong container đã kết thúc. Không dùng `SlowProbe` thay bằng chứng này.

**Verify:** `docker build -t masterenglish-api:m08-verify .`, test decoder bằng image đó và assert PID đã hết sau timeout/interruption. Nếu chỉ test host Java gọi binary container, ghi đúng phạm vi; không tuyên bố Java trong backend image đã được chạy nếu chưa chạy. Ghi đầy đủ lệnh, exit và XML.

**Điều kiện đóng:** decoder thật hoạt động bằng runtime cuối, giới hạn và process-exit proof đạt; không chỉ probe `-version`. Nếu phát hiện orphan process, tái hiện RED, sửa tầng sở hữu process, chạy GREEN rồi suite.

**Tiến độ 2026-11-27:** ĐÃ ĐÓNG (trên JAR/image cuối): `FfmpegMediaDecoderTests` (32/32 PASS) dùng Dockerfile backend + `build/libs/app.jar` SHA-256 `16bfee33db4eb6c984208059227778df59459bf630392d192366dc093682edb8`, xác nhận UID `10001`, `NATIVE_JAVA_DECODER_FORMATS_CONFIRMED`, `NATIVE_FFMPEG_TIMEOUT_EXIT_CONFIRMED` và `NATIVE_FFMPEG_INTERRUPTION_EXIT_CONFIRMED`; `docker build -t masterenglish-api:m08-verify .` cho image `sha256:3f2e5494a7ff1f26b63e813d97522d95ce11fb9c2b6f95baf3a2c72ae0137d1d`, `openjdk 21.0.13.1`, `ffmpeg/ffprobe 6.1.1-3ubuntu5`.

### Phase 3 — Proof toàn luồng giới hạn hai worker và crash an toàn (AC27)

**Việc làm theo thứ tự:**
1. Tạo harness test tối thiểu từ `MediaVerificationMinioIntegrationTests` + process harness hiện có: hai JVM backend dùng chung PostgreSQL17 và MinIO riêng, gọi workflow verification thật và decoder thật. Dùng user/role/source/receipt fixtures hợp lệ, không bỏ security/ledger để test dễ xanh.
2. Giữ hai tác vụ đang decode bằng barrier có tín hiệu bắt đầu; tác vụ thứ ba phải bị từ chối hoặc chờ đúng hợp đồng mà không bắt đầu tác vụ nặng. Assert đồng thời vật lý ≤2 bằng PID/lifecycle thực; DB slot count riêng không đủ.
3. Cho lease một worker hết hạn và ngắt connection thật trong khi process con vẫn chạy. Assert không cấp slot mới, worker stale không commit READY, không overwrite final; allocation vẫn đúng. Latch không được dựa vào thứ tự scheduler; dùng định danh task như bản sửa test vừa qua.
4. Tiêm crash JVM khi FFmpeg hoặc PUT đang chạy. Ghi rõ process con và PUT có còn khả năng chạy/ghi không. Slot crash-held phải giữ nguyên; cleanup không nhả reservation dựa trên TTL hay terminal state đơn thuần.
5. Chỉ kiểm thu hồi thủ công/có kiểm soát sau khi chứng minh worker, descendants và mọi I/O bất định đã dừng hoặc bị cô lập, rồi readback final size/hash và reconcile ledger. Không thêm API thu hồi production, heartbeat takeover hoặc hệ thống supervisor mới. Nếu không có bằng chứng an toàn cho thu hồi, giữ slot và ghi quy trình vận hành phải dừng/cô lập toàn bộ worker liên quan trước can thiệp.
6. Test healthy worker release: chỉ mở slot sau process và storage I/O kết thúc; tác vụ thứ ba mới được decode. Giữ test lỗi DB sau PUT, retry, CONTENT reservation và cleanup hiện có.

**Verify:** gate `*MediaVerificationIntegrationTests`, `*MediaVerificationMinioIntegrationTests`, `*MediaVerificationProcessTests`, `*AdminMediaIntegrationTests`, `*R2MediaStorageTests`, decoder tests và harness mới. Fake chỉ để tiêm lỗi; MinIO/binary/process thật cho proof. Không DB transaction quanh decode/PUT/read/delete; không tự sửa slot trực tiếp trong test rồi gọi đó là recovery production.

**Điều kiện đóng:** đủ proof giới hạn hai worker, stale fencing, không fake READY/nhả quota sai và fail-closed sau crash. Không bắt buộc auto recovery. Nếu proof không đạt, AC27 FAIL/NOT VERIFIED kèm ca cụ thể; không nới spec hoặc tăng timeout để che lỗi.

**Tiến độ 2026-11-27:** ĐÃ ĐÓNG (local): `MediaVerificationDualJvmIntegrationTests` (3/3 PASS) chạy 2 JVM backend thật trên image `Dockerfile` + `app.jar` dùng chung PG17 + MinIO + FFmpeg thật (xác minh PID FFmpeg sống, slot vật lý, `pg_terminate_backend`, lease takeover/stale fencing, lost PUT response & DB commit failure, `kill -9` JVM & `container.stop()` giữ slot fail-closed, `requireNoTransactionDuringIo()`), kết hợp `MediaVerificationMinioIntegrationTests` (1/1 PASS), `MediaVerificationIntegrationTests` (19/19 PASS), `MediaStagingIntegrationTests` (18/18 PASS), `AdminMediaIntegrationTests` (9/9 PASS gồm cleanup TEMP giữ reservation khi PUT claim chưa có staged receipt) và `MediaVerificationProcessTests` (1/1 PASS).

### Phase 4 — Gate độc lập và đóng ma trận

1. Đối chiếu 29 AC với assertion thực trên diff cuối; ghi `AC | trạng thái | test/assertion | file:line | lệnh/exit/XML/runtime | gap`. Tách 22 P0, AC21 và AC ngang. AC10/11/12/27/28 không được hưởng PASS chỉ từ full gate nếu thiếu runtime/entitlement proof.
2. Chạy tuần tự `git diff --check`, `.\gradlew.bat spotlessCheck check bootJar --max-workers=1 --no-daemon` với JDK21. Nếu `test` không thực thi, chạy lại bằng `--rerun-tasks` khi tài nguyên cho phép; chỉ một Gradle writer. Ghi mọi failure và đúng 7 baseline skips; không skip test mới.
3. Build/kiểm image sau mọi thay đổi production cuối. Gắn JAR hash/image ID với kết quả runtime; không dùng image trước thay đổi. Nếu chỉ sửa tài liệu sau gate, ghi điều đó thay chạy lại vô ích.
4. Review đúng diff bổ sung và vùng liên quan, giữ ranh giới module. Thực hiện trực tiếp, không subagent theo ràng buộc spec. Bug thật phải có RED/GREEN; không dọn code ngoài phạm vi.
5. Cập nhật `docs/admin/CONTRACT.md` nếu hành vi đổi; cập nhật ma trận, §8 và ledger. Ghi hạn chế crash-held slot, yêu cầu vận hành và các bước còn chặn. Không ghi hoàn thành khi thiếu bằng chứng.

**Điều kiện kết thúc:** ĐÃ ĐẠT (local) — 22/22 P0, AC21 bảo toàn, 7/7 AC ngang PASS trên snapshot cuối (`git diff --check` exit 0; Python `2/2` exit 0; `spotlessCheck check bootJar` **79 suites, 704 tests, 0 fail, 0 error, 7 baseline skips**, exit 0; image `sha256:3f2e5494a7ff1f26b63e813d97522d95ce11fb9c2b6f95baf3a2c72ae0137d1d` khớp JAR `16bfee33db4eb6c984208059227778df59459bf630392d192366dc093682edb8`). Chỉ sửa tài liệu bằng chứng sau gate cuối. `main`, DB tích hợp thật, R2 cloud, FE E2E và production giữ `NOT VERIFIED`. Không commit/push/merge/deploy.
