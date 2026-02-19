# Karaoke webOS & Android Remote System

Hệ thống hát Karaoke chuyên nghiệp cho webOS (LG TV) điều khiển bằng điện thoại Android.

## 🚀 Tính năng nổi bật
- **Đồng bộ hóa tức thì:** Kết nối Remote và TV chỉ bằng mã 6 số.
- **Tìm kiếm giọng nói (Tiếng Việt):** Nhận diện giọng nói siêu nhanh, chuyên dụng cho tìm nhạc Karaoke.
- **Điều khiển toàn diện:** Play, Pause, Next, Tăng/Giảm âm lượng trực tiếp từ điện thoại.
- **Hỗ trợ YouTube:** Tìm và phát video chất lượng cao từ YouTube.
- **Tự động cập nhật:** Cả TV và Remote đều hỗ trợ kiểm tra và cài đặt bản mới tự động.
- **Auto-discovery:** Tự động tìm thấy máy chủ trong mạng LAN qua giao thức UDP.

## 📂 Danh mục cài đặt (Releases)
Tất cả các bản cài đặt mới nhất được để trong thư mục `releases/`:
- **Android Remote (v1.8):** [`releases/KaraokeRemote_v1.8.apk`](releases/KaraokeRemote_v1.8.apk)
- **webOS Player (v1.1.0):** [`releases/KaraokePlayer_v1.1.0.ipk`](releases/KaraokePlayer_v1.1.0.ipk)

## 🛠 Hướng dẫn cài đặt

### 1. Cho TV (webOS)
- Sử dụng công cụ `ares-install` hoặc Developer Mode trên LG TV để cài file `.ipk`.
- Sau khi mở app, TV sẽ hiện mã Room 6 chữ số (ví dụ: `123456`).

### 2. Cho Android Remote
- Tải và cài đặt file `.apk` trên điện thoại.
- Mở ứng dụng, nhập mã Room hiện trên TV để bắt đầu điều khiển.
- **Tính năng giọng nói:** Bấm giữ nút Micro và nói tên bài hát (Ví dụ: "Vùng lá me bay karaoke").

### 3. Máy chủ (Server Node.js)
Hệ thống yêu cầu máy chủ Node.js chạy trong mạng LAN để điều hướng lệnh và cung cấp bản cập nhật:
```bash
cd karaoke-ws-server
npm install
node server.js
```

## 📝 Thông tin kỹ thuật
- **Frontend TV:** HTML5/CSS3/JavaScript (webOS SDK).
- **Android App:** Kotlin (Jetpack, WebSocket, Android Speech SDK).
- **Backend:** Node.js, WebSockets (ws), UDP/Datagram.

---
Phát triển bởi **PND Karaoke** - 2026.
