# ScreenWatcher (Android)

Quét **chữ** (OCR offline, ML Kit) hoặc **hình ảnh mẫu** (template matching) trong **một hoặc nhiều vùng** bạn chọn.
Khi khớp → chạy **macro** (chạm vào vị trí tìm thấy, chạm toạ độ cố định, vuốt, chờ, Back/Home...).

## Tạo APK bằng GitHub Actions (không cần cài Android Studio)

Repo đã có sẵn workflow `.github/workflows/build-apk.yml`: mỗi lần push lên `main` GitHub sẽ tự build `ScreenWatcher-debug.apk`.

### Cách 1: dùng terminal (cần `git` và [GitHub CLI](https://cli.github.com))
Chạy trong thư mục `ScreenWatcher` đã giải nén:

```bash
# 1. Đăng nhập GitHub (làm 1 lần)
gh auth login

# 2. Tạo repo và đẩy mã nguồn lên
git init -b main
git add .
git commit -m "ScreenWatcher: bản đầu tiên"
gh repo create screenwatcher --private --source=. --push

# 3. Theo dõi quá trình build (Ctrl+C để thoát, build chạy khoảng 3-6 phút)
gh run watch

# 4. Tải APK về thư mục ./apk
gh run download --name ScreenWatcher-debug-apk --dir apk
ls apk/ScreenWatcher-debug.apk
```

Chạy lại build mà không cần sửa mã:

```bash
gh workflow run "Build APK"
gh run watch
```

Tạo bản Release có kèm APK (để tải trực tiếp từ trang Releases):

```bash
git tag v1.0
git push origin v1.0
gh release view v1.0 --web
```

Nếu build lỗi, xem log:

```bash
gh run view --log-failed
```

### Cách 2: dùng giao diện web
1. Tạo repo mới trên github.com rồi tải toàn bộ nội dung thư mục này lên (giữ nguyên thư mục `.github`).
2. Vào tab **Actions** → **Build APK** → **Run workflow**.
3. Khi chạy xong, mở lần chạy đó, kéo xuống mục **Artifacts** và tải `ScreenWatcher-debug-apk`.

### Cài APK vào điện thoại
Copy `ScreenWatcher-debug.apk` sang máy, mở file và cho phép "cài từ nguồn không xác định". Hoặc qua USB (đã bật Gỡ lỗi USB):

```bash
adb install -r apk/ScreenWatcher-debug.apk
```

## Build bằng Android Studio
1. Cài Android Studio (Koala trở lên), mở thư mục `ScreenWatcher`, chờ Gradle sync (cần mạng).
2. Cắm điện thoại (Android 8.0+), bật Gỡ lỗi USB, bấm Run.
   Hoặc: `Build > Build APK(s)` rồi cài `app-debug.apk`.

## Cách dùng
1. Mở app, làm lần lượt 3 bước cấp quyền:
   1. Hiển thị trên ứng dụng khác
   2. Bật Dịch vụ trợ năng "ScreenWatcher" (cho phép chạm tự động)
   3. Cấp quyền chụp màn hình
2. **+ Thêm luật**:
   - Kiểu **Chữ**: nhập chữ cần tìm (dùng `|` để thêm lựa chọn, vd `Bỏ qua|Skip`). So khớp không phân biệt hoa/thường và dấu.
   - Kiểu **Hình ảnh**: bấm *Chọn ảnh mẫu trên màn hình*, khoanh đúng hình cần nhận diện.
   - *Chọn vùng*: kéo trên màn hình thật để khoanh vùng cần quét (thêm được nhiều vùng). Nút ✋ cho phép bạn chuyển sang app khác rồi ✏ để vẽ tiếp.
   - *Macro*, ví dụ:
     ```
     tap_match
     wait 500
     tap 540 1800
     ```
   - **Thêm thao tác lên màn hình**: chọn *Chạm*, *Chạm và giữ* hoặc *Vuốt*, đặt thời gian (ms), số lần và thời gian nghỉ giữa các lần, rồi chạm (hoặc kéo, với *Vuốt*) lên màn hình thật để chọn vị trí. Dòng lệnh tương ứng được thêm vào macro, vd `swipe 540 1600 540 600 300 times=5 gap=400`.
   - **Lặp lại**: sau khi chạy hết macro, luật có thể lặp lại N lần (1 = chỉ 1 lần, 0 = lặp mãi đến khi tắt theo dõi), nghỉ giữa các lần theo ms đã đặt.
   - **Chỉnh điểm chạm/vuốt trên màn hình**: mở lớp phủ hiện các thao tác đã có dưới dạng số 1, 2, 3… (xanh dương = chạm, cam = chạm giữ, xanh lá = vuốt kèm mũi tên). **Giữ ngón tay ~0,5 giây** ở chỗ trống để thêm điểm mới; **giữ 0,8 giây vào số** để mở cửa sổ cài đặt nhanh (đổi kiểu, thời gian, số lần, nghỉ giữa các lần, **thời gian chờ rồi chạm** (chờ bấy nhiêu ms trước khi chạm điểm đó), **kích thước nút** hoặc xoá; chạm chỗ trống hoặc bấm ✕ để đóng); **kéo** để dời vị trí (vuốt có thêm đầu kéo ở điểm cuối). Nút *▾ Ẩn* thu thanh nút phía dưới (hiện lại bằng nút ☰ nhỏ ở góc). Bấm *Xong* để ghi vào macro.
   - Bấm **Thử luật** để kiểm tra khớp hay không (chưa chạy macro), rồi **Lưu**.
3. Ở màn hình chính bật **Bật theo dõi**. Dừng bằng công tắc hoặc nút "Dừng hẳn" ở thông báo.
4. **Nút nổi** (hiện sau khi cấp quyền chụp màn hình, tắt/bật bằng công tắc "Hiện nút nổi"): kéo để di chuyển, **chạm** vào nút để hiện nút **▶ Run** (đang dừng) hoặc **■ Stop** (đang chạy) bên cạnh, bấm vào đó để chạy/dừng theo dõi (tự ẩn sau 4 giây); **giữ 0,5 giây** vào nút để mở/đóng bảng cài đặt gồm danh sách luật để bật/tắt từng luật (mỗi luật có nút **Thử** để quét kiểm tra nhanh: bảng và nút tạm ẩn, kết quả hiện bằng thông báo và vòng tròn đánh dấu chỗ khớp, chưa chạy macro), **+ Luật** (thêm luật mới), **Mở app**, **Ẩn nút**. Nút xanh = đang theo dõi, xám = đang tắt. Nút tự ẩn khi bạn đang khoanh vùng/ảnh mẫu.
4b. **Bộ luật đã lưu** (mục "Bộ luật đã lưu" ở màn hình chính): gõ tên rồi bấm **💾 Lưu luật hiện tại thành bộ luật** để lưu toàn bộ luật hiện có (kèm ảnh mẫu, chế độ tuần tự, số vòng, chu kỳ quét). Lưu được nhiều bộ cho nhiều tác vụ khác nhau. Mỗi bộ có: **▶ Chạy** (nạp rồi chạy theo dõi luôn, bắt đầu lại từ luật đầu), **Nạp** (chỉ nạp, để chỉnh thêm), **Ghi đè**, **Tên** (đổi tên), **Xoá**. Ở nút nổi: giữ 0,5 giây → **📁 Bộ luật** → chạm tên bộ luật để nạp và chạy ngay. Nạp một bộ luật sẽ thay thế danh sách luật đang dùng — hãy lưu lại trước nếu cần giữ.
5. **Chạy tuần tự** (công tắc ở màn hình chính): các luật đang bật chạy lần lượt theo thứ tự trong danh sách (đổi thứ tự bằng nút ▲ ▼). Mỗi bước chờ luật khớp rồi chạy macro, hết luật thì tính là xong 1 vòng và lặp lại theo **Số vòng** (1 = chỉ 1 lần, 0 = lặp mãi); đủ số vòng thì tự dừng theo dõi. Tiến độ hiện ở màn hình chính và trong bảng của nút nổi.
6. **Hiệu ứng chạm/vuốt**: khi macro chạy, vị trí chạm/vuốt hiện trên màn hình (vòng tròn, đường vuốt, số lần "2/5"). Tắt bằng công tắc tương ứng nếu hiệu ứng che vùng đang quét.

## Lưu ý
- Android 14+ hỏi lại quyền chụp màn hình mỗi lần khởi động dịch vụ.
- Một số app (ngân hàng, DRM...) chặn chụp màn hình hoặc chặn dịch vụ trợ năng; app đó sẽ hiện màn hình đen / không chạm được.
- Template matching tự thử nhiều tỉ lệ (0.4x–2.5x) nên ảnh trong vùng quét to/nhỏ hơn ảnh mẫu vẫn nhận được (log sẽ ghi tỉ lệ tìm thấy). Nên chọn hình có nhiều chi tiết, không quá nhỏ, và thu hẹp vùng quét để nhanh hơn. Nếu ảnh bị co giãn nhiều, có thể cần hạ ngưỡng khớp xuống ~0.7–0.8.
- Chỉ dùng cho mục đích hợp lệ và tuân thủ điều khoản của ứng dụng/trò chơi bạn tự động hoá.

## Ghi thao tác thành bộ luật (có xem trước)
1. Giữ nút nổi 0,5 giây → **● Ghi**. Bật sẵn Dịch vụ trợ năng. Thanh đỏ trên cùng hiện số bước, chạm **■ Dừng** để kết thúc.
2. Cứ thao tác bình thường (chạm, chạm giữ, vuốt). App bắt thao tác rồi chạm thay vào app bên dưới, đồng thời cắt ảnh mẫu quanh điểm chạm.
3. Dừng ghi → mở **màn hình xem trước**: hình thu nhỏ từng bước (khung đỏ = ảnh mẫu, chấm xanh = điểm chạm) và đánh giá ✅ Tốt / ⚠ Yếu / ❌ Rất yếu kèm lý do (ảnh đơn sắc, ít chi tiết, có hình giống ở chỗ khác trong vùng quét).
4. Mỗi bước có ô **Nhận diện bằng ảnh**. Bỏ chọn (hoặc bước ❌) → chạm đúng toạ độ cố định, nối vào macro của bước trước. Bước 1 luôn dùng ảnh.
5. **Lưu thành bộ luật** (luật tuần tự, 1 vòng) hoặc **Lưu và chạy ngay**. Danh sách luật hiện tại không bị đổi khi chỉ lưu.

Giới hạn: kéo thả liên tục (cần xoay nhân vật) không mượt vì thao tác được phát lại sau khi nhấc tay; màn hình chặn chụp sẽ không có ảnh mẫu.
