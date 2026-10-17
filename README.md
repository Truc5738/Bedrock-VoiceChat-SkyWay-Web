# Bedrock VoiceChat SkyWay Web

Giải pháp thử nghiệm để xây dựng trò chuyện thoại cho người chơi Minecraft Java và Bedrock, gồm plugin Paper, các ứng dụng voice client và một trang web dùng SkyWay WebRTC.

> **Trạng thái:** Đang phát triển. README mô tả cấu trúc và giới hạn hiện tại; không có nghĩa toàn bộ luồng thoại đã được kiểm thử thực tế trên máy chủ và điện thoại.

## Mục lục

- [Tổng quan](#tổng-quan)
- [Các thành phần](#các-thành-phần)
- [Yêu cầu](#yêu-cầu)
- [Build bằng GitHub Actions](#build-bằng-github-actions)
- [Triển khai trang web](#triển-khai-trang-web)
- [Thiết lập SkyWay an toàn](#thiết-lập-skyway-an-toàn)
- [Giới hạn quan trọng](#giới-hạn-quan-trọng)
- [Giao thức gateway TCP](#giao-thức-gateway-tcp)
- [Tài liệu](#tài-liệu)
- [Khắc phục sự cố](#khắc-phục-sự-cố)

## Tổng quan

Mục tiêu của dự án là cung cấp nền tảng để phát triển voice chat cho máy chủ Minecraft, bao gồm giao diện quản lý kênh thoại và các client thu/phát âm thanh.

Các chức năng được hướng tới:

- Bật/tắt micro và âm thanh đầu ra.
- Kênh thoại và nền tảng cho kênh riêng tư.
- Hiển thị số người đang nói.
- Quy tắc khoảng cách, thế giới và kênh để định tuyến âm thanh.
- Điều chỉnh âm lượng hoặc tắt tiếng từng người chơi.
- Hỗ trợ giao diện cho người chơi Bedrock thông qua Floodgate/Cumulus.
- Client trình duyệt dựa trên SkyWay WebRTC để thử nghiệm thoại trên thiết bị di động.

Một số mục là nền tảng hoặc đang phát triển, không nên hiểu là tất cả đã hoàn thiện.

## Các thành phần

| Thành phần | Đường dẫn | Vai trò |
|---|---|---|
| Plugin Paper | `src/` | Lệnh, giao diện, trạng thái người chơi và logic máy chủ |
| Java voice client | `voice-client/` | Client thoại Java |
| Android voice client | `voice-client-android/` | Ứng dụng Android |
| SkyWay web client | `web/index.html` | Trang web thoại chạy trong trình duyệt |
| GitHub Actions | `.github/workflows/` | Build và triển khai GitHub Pages |
| Wiki | `docs/wiki/` | Hướng dẫn và tài liệu kỹ thuật |

## Yêu cầu

- GitHub để chạy workflow build.
- JDK theo cấu hình trong workflow tương ứng.
- Máy chủ Paper tương thích với phiên bản API được khai báo trong `build.gradle.kts`.
- Floodgate nếu cần nhận diện người chơi Bedrock và hiển thị form Bedrock.
- Cổng TCP có thể truy cập từ client nếu sử dụng gateway tích hợp.
- SkyWay project và Auth Token hợp lệ nếu dùng trang web SkyWay.

**Lưu ý về phiên bản:** Hãy kiểm tra `build.gradle.kts`, các tệp Gradle của từng client và workflow trước khi chọn phiên bản Minecraft/Java cho máy chủ. Phiên bản trong cấu hình build có thể thay đổi theo commit.

## Build bằng GitHub Actions

1. Mở tab **Actions** của repository.
2. Chọn workflow build.
3. Chạy workflow hoặc đẩy commit mới lên nhánh `main`.
4. Mở lần chạy tương ứng và kiểm tra từng job.
5. Nếu build thành công, tải các artifact ở cuối trang workflow run.

Mở [GitHub Actions](../../actions) để xem trạng thái build mới nhất.

Nếu build thất bại, hãy xem log của bước lỗi đầu tiên. Một bước bị bỏ qua (`skipped`) không tự động có nghĩa là build thất bại; có thể bước đó chỉ chạy khi thỏa điều kiện nhất định.

## Triển khai trang web

Trang web nằm tại [`web/index.html`](web/index.html). Workflow triển khai GitHub Pages nằm ở [`.github/workflows/deploy-pages.yml`](.github/workflows/deploy-pages.yml).

Để bật GitHub Pages:

1. Mở **Settings → Pages** trong repository.
2. Chọn **GitHub Actions** làm nguồn triển khai.
3. Đẩy thay đổi trong thư mục `web/` lên nhánh `main`, hoặc chạy workflow triển khai thủ công nếu workflow hỗ trợ.
4. Kiểm tra trạng thái deployment trong tab **Actions**.

GitHub Pages chỉ phục vụ các tệp tĩnh. Nó không tự tạo SkyWay Auth Token và cũng không cung cấp máy chủ backend.

## Thiết lập SkyWay an toàn

Trang web yêu cầu **SkyWay Auth Token ngắn hạn** do hệ thống của bạn cấp.

- Không bao giờ đưa SkyWay Secret Key vào `web/index.html`, repository công khai, mã JavaScript chạy trong trình duyệt hoặc ảnh chụp màn hình.
- Không dùng token dài hạn có quyền quá rộng.
- Với triển khai thực tế, tạo token ở backend đáng tin cậy, giới hạn quyền và thời gian sống của token.
- Nếu token bị lộ, thu hồi hoặc thay thế token đó trong bảng điều khiển SkyWay.
- Chỉ dùng thông tin cấu hình phù hợp với SkyWay project của bạn.

## Giới hạn quan trọng

- **Plugin Paper không thể tự đọc micro của người chơi.** Minecraft Java/Bedrock vanilla không cung cấp dữ liệu micro cho plugin máy chủ.
- **Floodgate không thu âm.** Floodgate hỗ trợ nhận diện người chơi Bedrock; Cumulus có thể cung cấp giao diện form, nhưng không phải hệ thống voice capture.
- **Trang web không tự biết vị trí trong Minecraft.** Để có âm thanh theo khoảng cách, cần một cầu nối vị trí đáng tin cậy từ máy chủ/game tới hệ thống thoại.
- **GitHub Pages không phải backend phát token.** Cần backend riêng nếu muốn cấp token an toàn.
- **WebRTC phụ thuộc mạng.** Kết nối có thể dùng UDP hoặc cơ chế chuyển tiếp/fallback tùy mạng và cấu hình dịch vụ.
- **Chưa nên coi đây là bản production** cho tới khi kiểm thử thực tế kết nối, quyền truy cập, xử lý mất kết nối và bảo mật token.

## Giao thức gateway TCP

Plugin có gateway thoại TCP tích hợp. Cấu hình cổng mặc định trong mã hiện tại là `26467`; hãy xác nhận lại trong `config.yml` và mã nguồn của đúng phiên bản bạn đang chạy.

Khung gói tin được mô tả như sau:

| Trường | Kích thước |
|---|---:|
| Magic ASCII `MVC1` | 4 byte |
| Loại gói | 1 byte |
| UUID người chơi | 16 byte |
| Số thứ tự | 4 byte |
| Độ dài payload | 4 byte |
| Payload | N byte |

Loại gói được thiết kế:

- `1 HELLO`: token gateway dạng UTF-8, sequence bằng `0`.
- `2 AUDIO`: khung âm thanh đã mã hóa, thường là Opus.
- `3 GOODBYE`: payload rỗng.

Client phải xác thực bằng `HELLO` trước khi gửi `AUDIO`; UUID trong gói tin phải khớp với phiên đã xác thực. Hãy xem [Protocol](docs/wiki/Protocol.md) trước khi tự viết client tương thích.

> Không công khai token gateway. Nếu token mặc định hoặc token hiện tại đã bị lộ, hãy thay thế nó và kiểm tra cấu hình máy chủ.

## Tài liệu

- [Trang chủ Wiki](docs/wiki/Home.md)
- [Cài đặt](docs/wiki/Installation.md)
- [Cấu hình](docs/wiki/Configuration.md)
- [Các client](docs/wiki/Clients.md)
- [Giao thức](docs/wiki/Protocol.md)
- [Kiến trúc](docs/wiki/Architecture.md)
- [Khắc phục sự cố](docs/wiki/Troubleshooting.md)

## Đóng góp và báo lỗi

Khi báo lỗi, hãy đính kèm:

- Phiên bản Minecraft, Paper và Java.
- Tên workflow và đường dẫn tới lần chạy Actions bị lỗi.
- Log liên quan đã xóa token, mật khẩu, địa chỉ riêng tư và dữ liệu nhạy cảm.
- Các bước tái hiện lỗi và kết quả mong đợi.

Không đăng SkyWay Secret Key, Auth Token, token gateway, mật khẩu RDP hoặc thông tin đăng nhập trong issue/log công khai.

## Quy tắc giao diện

Các thông báo, cấu hình và giao thức của plugin nên tránh emoji để giữ giao diện nhất quán và hạn chế vấn đề tương thích.
