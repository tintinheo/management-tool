"""Generates app/src/main/assets/curriculum.json (8 weeks x 5 sessions). Run: python tools/gen_curriculum.py app/src/main/assets/curriculum.json"""
import json, sys

def warm(extra=""):
    return {"minutes": 3, "title": "Lên dây", "tool": "TUNER", "visual": {"type": "strings"},
            "text": "Gảy từng dây buông, vặn khoá đến khi kim vào giữa và chuyển xanh. Luôn lên dây từ thấp lên (vặn chùng rồi căng dần tới nốt) để dây giữ tông lâu hơn." + (" " + extra if extra else "")}

def fingers(minutes=3, bpm=60):
    return {"minutes": minutes, "title": "Khởi động ngón 1-2-3-4", "tool": "METRONOME", "bpm": bpm, "pattern": "DOWN_4",
            "text": "Đặt ngón trỏ ở ngăn 1 dây 6, gảy; thêm ngón giữa ngăn 2, gảy; áp út ngăn 3; út ngăn 4. Mỗi click một nốt, rồi chuyển sang dây 5… đến dây 1. Giữ các ngón cũ trên dây nếu được.",
            "visual": {"type": "fretboard", "frets": 4, "dots": [
                {"s": 6, "f": 1, "label": "1"}, {"s": 6, "f": 2, "label": "2"}, {"s": 6, "f": 3, "label": "3"}, {"s": 6, "f": 4, "label": "4"}],
                "caption": "Số trên chấm là ngón tay: 1 trỏ · 2 giữa · 3 áp út · 4 út. Mỗi ngón phụ trách một ngăn."}}

def review(text="Buổi hôm nay chỗ nào khó nhất? Ghi nhớ để buổi sau tập chậm lại đúng chỗ đó."):
    return {"minutes": 2, "title": "Tự đánh giá", "text": text}

def chord(name, minutes, text):
    return {"minutes": minutes, "title": f"Hợp âm {name}", "tool": "CHORD_CHECK", "chord": name,
            "visual": {"type": "chord", "chord": name}, "text": text}

def changes(a, b, minutes, text, visual=None):
    st = {"minutes": minutes, "title": f"Chuyển {a} ↔ {b}", "tool": "CHANGES", "chord": a, "chordB": b, "text": text}
    if visual: st["visual"] = visual
    return st

def metro(title, minutes, bpm, pattern, text, visual=True):
    st = {"minutes": minutes, "title": title, "tool": "METRONOME", "bpm": bpm, "pattern": pattern, "text": text}
    strokes = {"DOWN_4": "D.D.D.D.", "DOWN_UP": "DUDUDUDU", "BALLAD": "D.DU.UDU"}.get(pattern, pattern)
    if visual: st["visual"] = {"type": "strum", "pattern": strokes}
    return st

def rhythm(title, minutes, bpm, pattern, text):
    strokes = {"DOWN_4": "D.D.D.D.", "DOWN_UP": "DUDUDUDU", "BALLAD": "D.DU.UDU"}[pattern]
    return {"minutes": minutes, "title": title, "tool": "RHYTHM", "bpm": bpm, "pattern": pattern, "text": text,
            "visual": {"type": "strum", "pattern": strokes}}

def song(file, minutes, title, text, strum=None):
    st = {"minutes": minutes, "title": title, "tool": "SONG", "song": file, "text": text}
    if strum: st["visual"] = {"type": "strum", "pattern": strum}
    return st

def session(sid, title, goal, steps):
    return {"id": sid, "title": title, "goal": goal, "steps": steps}

BASS_DOTS = [{"s": 6, "f": 0, "label": "Em", "root": True}, {"s": 5, "f": 0, "label": "Am", "root": True},
             {"s": 5, "f": 3, "label": "C", "root": True}, {"s": 6, "f": 3, "label": "G", "root": True},
             {"s": 4, "f": 0, "label": "D", "root": True}]

weeks = []

# ---------------- Week 1 ----------------
weeks.append({"number": 1, "title": "Làm quen cây đàn",
  "goal": "Ngồi đúng tư thế, biết tên 6 dây, tự lên dây bằng tuner, bấm nốt sạch và gảy được hai hợp âm đầu tiên: Em và Am.",
  "test": [
    {"type": "tuned", "text": "Lên đủ 6 dây trong tuner"},
    {"type": "chord", "chord": "Em", "text": "Em được micro chấm đúng 3 lần liền"},
    {"type": "chord", "chord": "Am", "text": "Am được micro chấm đúng 3 lần liền"},
    {"type": "quiz", "min": 7, "text": "Đố nốt: đúng ít nhất 7/10"}],
  "sessions": [
    session("w1s1", "Cây đàn và 6 dây", "Biết cầm đàn, gọi tên 6 dây và lên dây lần đầu.", [
      {"minutes": 4, "title": "Tư thế ngồi", "text": "Ngồi ghế không tay vịn, lưng thẳng. Eo đàn đặt lên đùi phải, mặt đàn thẳng đứng (đừng ngửa đàn lên để nhìn). Cánh tay phải gác lên cạnh thùng đàn, cổ tay trái thả lỏng, ngón cái trái đặt sau cần đàn khoảng giữa."},
      {"minutes": 5, "title": "Tên 6 dây", "tool": "NOTES", "visual": {"type": "strings"}, "text": "Dây được đánh số từ dưới lên: dây 1 mảnh nhất nằm gần sàn, dây 6 to nhất nằm gần mặt bạn. Chạm vào từng dây trên hình để nghe. Đọc to: dây 6 E (Mi), 5 A (La), 4 D (Rê), 3 G (Sol), 2 B (Si), 1 E (Mi)."},
      {"minutes": 8, "title": "Lên dây lần đầu", "tool": "TUNER", "visual": {"type": "strings"}, "text": "Mở tuner, gảy dây 6 và để ngân. Kim lệch trái (thấp) thì vặn căng dây, lệch phải (cao) thì vặn chùng. Vặn từng chút một — dây mới rất dễ đứt nếu vặn quá tay. Làm lần lượt cả 6 dây, sau đó kiểm tra lại một lượt vì dây kéo cần đàn làm các dây khác lệch theo."},
      metro("Gảy dây buông theo nhịp", 6, 60, "DOWN_4", "Mỗi click gảy xuống một dây, đi từ dây 6 tới dây 1 rồi quay lại, vừa gảy vừa đọc tên dây. Dùng ngón cái hoặc phím gảy, cổ tay thả lỏng như phẩy nước."),
      review("Bạn có lên được cả 6 dây không? Tuần đầu, mỗi buổi đều lên dây — dần dần bạn sẽ nghe ra khi đàn lệch.")]),
    session("w1s2", "Bấm phím đúng cách", "Bấm một nốt kêu trong, không rè; biết các nốt trên dây 1 và 2.", [
      warm(),
      {"minutes": 6, "title": "Đặt ngón ở đâu?", "tool": "NOTES", "visual": {"type": "press"}, "text": "Ngón bấm đặt sát thanh phím kim loại (phía thùng đàn), không đè lên nó và không đặt giữa ô. Dùng đầu ngón, ngón cong, móng tay trái cắt ngắn. Thử bấm dây 1 ngăn 3 ở ba chỗ như hình để nghe khác biệt."},
      fingers(6),
      {"minutes": 6, "title": "Nốt trên dây 1 và 2", "tool": "NOTES", "visual": {"type": "notes", "frets": 3, "strings": [1, 2]}, "text": "Dây 1: buông = E, ngăn 1 = F, ngăn 3 = G. Dây 2: buông = B, ngăn 1 = C, ngăn 3 = D. Ngăn 1 dùng ngón trỏ, ngăn 3 dùng ngón áp út. Chạm vào hình để nghe, rồi đánh lại trên đàn và nói tên nốt."},
      review("Nốt có bị rè không? Rè thường do ngón xa thanh phím hoặc ấn bằng bụng ngón.")]),
    session("w1s3", "Hợp âm Em", "Bấm Em sạch cả 6 dây và quạt xuống đều theo metronome.", [
      warm(),
      chord("Em", 8, "Ngón giữa ngăn 2 dây 5, ngón áp út ngăn 2 dây 4. Hai ngón đứng thẳng để không chạm dây 3 bên dưới. Gảy chậm từng dây từ 6 xuống 1 — dây nào câm hay rè thì chỉnh ngón rồi gảy lại. Sau đó quạt cả 6 dây và nhờ micro chấm."),
      metro("Quạt xuống 4 phách", 8, 60, "DOWN_4", "Giữ Em, mỗi click quạt xuống một lần. Đếm to \"1 2 3 4\". Quạt từ cổ tay, không vung cả cánh tay. Đến phách 4, nhấc tay trái khỏi dây rồi đặt lại Em — tập cho tay nhớ hình."),
      fingers(4),
      review()]),
    session("w1s4", "Hợp âm Am", "Bấm Am, chuyển chậm giữa Em và Am không dừng nhịp.", [
      warm(),
      chord("Am", 7, "Am giống hình Em dời xuống một dây (ngón giữa dây 4, áp út dây 3), thêm ngón trỏ ngăn 1 dây 2. Không gảy dây 6 — bắt đầu quạt từ dây 5 (A, nốt bass của Am)."),
      changes("Em", "Am", 6, "Ngón giữa và áp út giữ nguyên hình, chỉ trượt cùng nhau xuống một dây; ngón trỏ đặt thêm vào. Tập chậm: đổi, gảy, kiểm tra, đổi lại. Đừng nhìn số lần — chỉ cần đúng.", {"type": "chord", "chord": "Am"}),
      metro("Em – Am theo nhịp", 7, 60, "DOWN_4", "Em 4 phách, Am 4 phách, lặp lại. Nếu chưa kịp đổi, cứ quạt dây buông ở phách 1 — tay phải không bao giờ dừng."),
      review()]),
    session("w1s5", "Ôn tuần 1 & kiểm tra", "Đạt các mục kiểm tra để mở tuần 2.", [
      warm("Hôm nay tuner sẽ ghi nhận khi bạn lên đủ 6 dây."),
      chord("Em", 4, "Quạt chậm, để ngân, quạt lại. Cần 3 lần liền được chấm đúng."),
      chord("Am", 4, "Nhớ không gảy dây 6. Cần 3 lần liền được chấm đúng."),
      {"minutes": 6, "title": "Đố nốt", "tool": "QUIZ", "visual": {"type": "notes", "frets": 3, "strings": [1, 2, 6]}, "text": "10 câu: app hỏi một nốt, bạn chạm đúng vị trí hoặc đánh nốt đó trên đàn. Ôn lại hình trước khi bắt đầu: dây 6 giống dây 1 (E F G), chỉ trầm hơn hai quãng tám."},
      metro("Em – Am liền mạch", 5, 60, "DOWN_4", "Chạy Em–Am 8 lượt không dừng nhịp."),
      review("Mở thẻ \"Tuần 1\" ở màn Hôm nay để xem bạn đã đạt những mục nào.")]),
  ]})

# ---------------- Week 2 ----------------
weeks.append({"number": 2, "title": "C, G và giữ nhịp",
  "goal": "Thêm C và G — bốn hợp âm đủ đệm hàng trăm bài. Chuyển hợp âm trong lúc tay phải vẫn giữ nhịp 60 BPM.",
  "test": [
    {"type": "chord", "chord": "C", "text": "C được micro chấm đúng 3 lần liền"},
    {"type": "chord", "chord": "G", "text": "G được micro chấm đúng 3 lần liền"},
    {"type": "changes", "chord": "C", "chordB": "G", "min": 20, "text": "Chuyển C ↔ G: ít nhất 20 lần trong 1 phút"},
    {"type": "self", "text": "Chạy Em–Am–C–G mỗi hợp âm 4 phách ở 60 BPM, 4 vòng không dừng nhịp"}],
  "sessions": [
    session("w2s1", "Hợp âm C", "Bấm C sạch, nghe rõ dây 3 và dây 1 buông.", [
      warm(), fingers(3),
      chord("C", 8, "Áp út ngăn 3 dây 5 (nốt C — bass), giữa ngăn 2 dây 4, trỏ ngăn 1 dây 2. Ngón phải cong cao để dây 3 và dây 1 buông vẫn kêu. Gảy từ dây 5."),
      changes("Am", "C", 6, "Mẹo: ngón trỏ (dây 2 ngăn 1) và ngón giữa (dây 4 ngăn 2) KHÔNG nhấc lên — chỉ có ngón áp út di chuyển từ dây 3 sang dây 5 ngăn 3. Chuyển Am↔C là cặp dễ nhất.", {"type": "chord", "chord": "C"}),
      metro("Quạt C và Am", 5, 60, "DOWN_4", "Am 4 phách – C 4 phách. Đếm to, đổi đúng ở số 1."),
      review()]),
    session("w2s2", "Hợp âm G", "Bấm G với dây 6 làm bass; chuyển Em ↔ G.", [
      warm(), fingers(3),
      chord("G", 8, "Giữa ngăn 3 dây 6 (G — bass), trỏ ngăn 2 dây 5, áp út ngăn 3 dây 1. Dây 4, 3, 2 buông phải kêu — đầu ngón đứng thẳng. Quạt cả 6 dây."),
      changes("Em", "G", 6, "Ngón giữa của Em (dây 5 ngăn 2) đổi thành ngón trỏ ở G. Tập đặt cả ba ngón cùng lúc, như in con dấu.", {"type": "chord", "chord": "G"}),
      metro("Quạt G – Em", 5, 60, "DOWN_4", "G 4 phách – Em 4 phách, lặp lại."),
      review()]),
    session("w2s3", "Chuyển C ↔ G", "Bắt đầu đo one-minute changes cho cặp khó nhất tuần.", [
      warm(),
      {"minutes": 4, "title": "Xem hai hình cạnh nhau", "visual": {"type": "chord", "chord": "G"}, "text": "So sánh: C dùng dây 5 làm bass, G dùng dây 6. Khi đổi, nhấc cả bàn tay một chút, giữ hình ngón \"lơ lửng\" và hạ xuống cùng lúc — không đặt từng ngón một."},
      changes("C", "G", 8, "Ba vòng 1 phút. Ghi lại số lần — mục tiêu tuần này là 20. Sạch quan trọng hơn nhanh: micro chỉ đếm khi nghe ra đúng hợp âm.", {"type": "chord", "chord": "C"}),
      metro("C – G theo nhịp", 6, 60, "DOWN_4", "C 4 phách, G 4 phách. Phách 4 là lúc tay trái bắt đầu rời đi — chấp nhận phách 4 hơi đục."),
      review()]),
    session("w2s4", "Giữ nhịp khi đổi hợp âm", "Bài đầu tiên với hai hợp âm C và G.", [
      warm(),
      metro("Vòng Em – Am – C – G", 8, 60, "DOWN_4", "Mỗi hợp âm 4 phách. Bí quyết của người đệm hát: tay phải là đồng hồ, không bao giờ chờ tay trái. Chưa kịp bấm thì vẫn quạt (dây buông) đúng phách."),
      song("03_bac_kim_thang.cho", 8, "Bài Bắc Kim Thang", "Chỉ có C và G, đổi hợp âm ở chữ cuối câu. Lần đầu chỉ đàn, lần hai đọc lời theo, lần ba thử hát nhỏ. Bấm ▶ để lời tự cuộn.", "D.DU"),
      changes("C", "G", 4, "Một vòng 1 phút để so với hôm trước."),
      review()]),
    session("w2s5", "Ôn tuần 2 & kiểm tra", "Đạt các mục kiểm tra để mở tuần 3.", [
      warm(),
      chord("C", 3, "3 lần liền đúng."), chord("G", 3, "3 lần liền đúng."),
      changes("C", "G", 6, "Làm vài vòng, lấy kết quả tốt nhất. Mục tiêu 20 lần."),
      metro("Vòng 4 hợp âm", 6, 60, "DOWN_4", "Em–Am–C–G, 4 vòng liền không dừng. Làm được thì đánh dấu mục tự kiểm tra."),
      review()]),
  ]})

# ---------------- Week 3 ----------------
weeks.append({"number": 3, "title": "D và quạt xuống–lên",
  "goal": "Hợp âm D, nhịp quạt xuống–lên đều tay, vòng G–D–Em–C, và biết nốt bass của từng hợp âm nằm ở dây nào.",
  "test": [
    {"type": "chord", "chord": "D", "text": "D được micro chấm đúng 3 lần liền"},
    {"type": "changes", "chord": "G", "chordB": "D", "min": 20, "text": "Chuyển G ↔ D: ít nhất 20 lần trong 1 phút"},
    {"type": "quiz", "min": 8, "text": "Đố nốt: đúng ít nhất 8/10"},
    {"type": "rhythm", "pattern": "DUDUDUDU", "bpm": 70, "maxMs": 60, "text": "Chấm nhịp: quạt xuống–lên ở 70 BPM, lệch trung bình ≤ 60 ms"}],
  "sessions": [
    session("w3s1", "Hợp âm D", "Bấm D trên 4 dây mỏng, gảy từ dây 4.", [
      warm(), fingers(3, 70),
      chord("D", 8, "Hình tam giác: trỏ ngăn 2 dây 3, áp út ngăn 3 dây 2, giữa ngăn 2 dây 1. Chỉ gảy từ dây 4 (D buông — nốt bass). Dây 1 hay bị câm: cong ngón giữa hơn."),
      changes("G", "D", 7, "Ngón áp út là \"ngón neo\": ở G nó ở dây 1 ngăn 3, ở D nó ở dây 2 ngăn 3 — chỉ trượt một dây. Đổi xung quanh ngón đó.", {"type": "chord", "chord": "D"}),
      metro("Quạt D – G", 5, 60, "DOWN_4", "D 4 phách, G 4 phách. Nhớ: D gảy từ dây 4, G từ dây 6 — tay phải cũng phải đổi chỗ bắt đầu."),
      review()]),
    session("w3s2", "Quạt xuống – lên", "Tay phải đi như con lắc: xuống ở số, lên ở \"&\".", [
      warm(),
      metro("Quạt DU trên dây câm", 5, 60, "DOWN_UP", "Tay trái đặt nhẹ lên dây cho câm tiếng. Quạt xuống ở số, lên ở \"&\": 1 & 2 & 3 & 4 &. Chỉ nghe tiếng \"chách\" đều — đây là luyện tay phải thuần tuý."),
      metro("Quạt DU trên Em", 7, 60, "DOWN_UP", "Giữ Em. Cú lên nhẹ hơn, chỉ cần chạm 3–4 dây mỏng. Tay không dừng giữa các cú."),
      rhythm("Chấm nhịp lần đầu", 6, 60, "DOWN_UP", "Mở Chấm nhịp quạt. Lần đầu bấm \"Đo ngay\" để đo độ trễ (quạt đúng 12 tiếng click, khoảng 20 giây). Sau đó chấm 8 ô nhịp ở 60 BPM: ô sáng xanh là đúng phách, vàng là hơi lệch, đỏ là lệch nhiều."),
      changes("Em", "C", 4, "Một vòng để giữ tay trái."),
      review()]),
    session("w3s3", "Vòng G – D – Em – C", "Vòng 4 hợp âm phổ biến nhất trong nhạc pop.", [
      warm(),
      changes("Em", "C", 5, "Cặp còn thiếu trong vòng. Ngón giữa dời từ dây 5 sang dây 4 (vẫn ngăn 2), áp út sang dây 5 ngăn 3, ngón trỏ đặt thêm vào dây 2 ngăn 1. Tập chậm."),
      song("01_vong_4_hop_am.cho", 10, "Bài luyện vòng 4 hợp âm", "Mỗi hợp âm 1 ô nhịp (đếm 1 2 3 4). Bật nhịp ở thanh dưới (biểu tượng đồng hồ) để có click. Vòng 1–4 quạt xuống, vòng 5–8 thử xuống–lên.", "D.D.D.D."),
      metro("Vòng với quạt DU", 6, 60, "DOWN_UP", "G–D–Em–C mỗi hợp âm 8 cú DU."),
      review()]),
    session("w3s4", "Nốt bass của mỗi hợp âm", "Biết nốt trên dây 4–5–6 và dây nào là bass của từng hợp âm.", [
      warm(),
      {"minutes": 6, "title": "Nốt trên dây 6, 5, 4", "tool": "NOTES", "visual": {"type": "notes", "frets": 3, "strings": [4, 5, 6]}, "text": "Dây 6: E (buông) F (1) G (3). Dây 5: A (buông) B (2) C (3). Dây 4: D (buông) E (2) F (3). Quy tắc nhớ: giữa B–C và E–F chỉ cách 1 ngăn, các nốt khác cách 2 ngăn."},
      {"minutes": 6, "title": "Bass nằm ở đâu?", "tool": "NOTES", "visual": {"type": "fretboard", "frets": 3, "dots": BASS_DOTS, "caption": "Mỗi chấm là nốt bass (nốt gốc) của hợp âm: Em = dây 6 buông, Am = dây 5 buông, C = dây 5 ngăn 3, G = dây 6 ngăn 3, D = dây 4 buông."}, "text": "Nốt bass là nốt thấp nhất của hợp âm và thường trùng tên hợp âm. Gảy riêng nốt bass rồi quạt phần còn lại — tiếng đàn sẽ \"có đáy\" hơn nhiều. Chạm vào từng chấm để nghe."},
      {"minutes": 6, "title": "Đố nốt", "tool": "QUIZ", "visual": {"type": "notes", "frets": 3}, "text": "10 câu trên cả 6 dây. Mục tiêu tuần này: 8/10."},
      metro("Bass rồi quạt", 4, 60, "DOWN_4", "Mỗi hợp âm trong G–D–Em–C: phách 1 gảy riêng nốt bass, phách 2–3–4 quạt xuống."),
      review()]),
    session("w3s5", "Ôn tuần 3 & kiểm tra", "Đạt các mục kiểm tra để mở tuần 4.", [
      warm(),
      chord("D", 3, "3 lần liền đúng."),
      changes("G", "D", 6, "Mục tiêu 20 lần."),
      {"minutes": 5, "title": "Đố nốt", "tool": "QUIZ", "text": "Mục tiêu 8/10."},
      rhythm("Kiểm tra: chấm nhịp 70 BPM", 6, 70, "DOWN_UP", "Giữ một hợp âm, quạt xuống–lên 8 ô nhịp. Đạt khi lệch trung bình ≤ 60 ms và micro nghe được ít nhất 80% cú quạt. Không đạt thì giảm còn 60, rồi thử lại 70."),
      review()]),
  ]})

# ---------------- Week 4 ----------------
weeks.append({"number": 4, "title": "Mẫu quạt Ballad, bài đầu tiên",
  "goal": "Mẫu quạt D - D U - U D U của rất nhiều bài, và đàn trọn những bài mẫu đầu tiên (chưa cần hát).",
  "test": [
    {"type": "changes", "chord": "Em", "chordB": "C", "min": 20, "text": "Chuyển Em ↔ C: ít nhất 20 lần trong 1 phút"},
    {"type": "rhythm", "pattern": "D.DU.UDU", "bpm": 70, "maxMs": 60, "text": "Chấm nhịp: mẫu Ballad ở 70 BPM, lệch trung bình ≤ 60 ms"},
    {"type": "self", "text": "Chạy vòng G–D–Em–C 8 lần với mẫu Ballad ở 70 BPM không dừng nhịp"},
    {"type": "self", "text": "Đàn hết bài Twinkle Twinkle không dừng"}],
  "sessions": [
    session("w4s1", "Mẫu quạt Ballad", "Thuộc mẫu D . D U . U D U trên một hợp âm.", [
      warm(),
      metro("Nửa đầu: D . D U", 6, 60, "BALLAD", "Tay vẫn đi xuống–lên liên tục như tuần 3, chỉ \"trượt\" (không chạm dây) ở chỗ có dấu chấm. Tập nửa đầu ô nhịp: xuống, (trượt lên), xuống, lên."),
      metro("Cả mẫu trên G", 8, 60, "BALLAD", "Đọc to: \"xuống – xuống lên – lên xuống lên\". Chỗ khó là phách 3: tay đi xuống nhưng không chạm dây."),
      metro("Ballad trên Em và C", 6, 60, "BALLAD", "Mỗi hợp âm 1 ô nhịp. Đổi hợp âm trong lúc tay trượt ở cuối ô."),
      review()]),
    session("w4s2", "Ballad với vòng hợp âm", "Áp mẫu Ballad lên vòng 4 hợp âm.", [
      warm(),
      changes("Em", "C", 5, "Cặp còn chậm nhất trong vòng — một vòng 1 phút."),
      song("01_vong_4_hop_am.cho", 10, "Vòng 4 hợp âm với Ballad", "Đặt nhịp 60 BPM, dùng mẫu Ballad cho mọi ô nhịp. Lên 70 khi đã chạy được 4 vòng liền.", "D.DU.UDU"),
      metro("Ballad 70 BPM", 6, 70, "BALLAD", "Thử tốc độ của bài kiểm tra."),
      review()]),
    session("w4s3", "Bài Twinkle Twinkle", "Đàn trọn bài với G, C, D (chưa hát).", [
      warm(),
      changes("G", "C", 5, "Twinkle đổi G↔C nhiều nhất."),
      song("04_twinkle.cho", 12, "Twinkle, Twinkle, Little Star", "Quạt xuống mỗi phách, đổi hợp âm đúng ở chữ có hợp âm. Lần đầu để cuộn chậm 0.7×. Ngân nga giai điệu trong đầu khi đàn.", "D.D.D.D."),
      metro("Quạt Twinkle với Ballad", 4, 70, "BALLAD", "Thử thay quạt xuống bằng mẫu Ballad cho bài Twinkle."),
      review()]),
    session("w4s4", "Nhịp 3/4: Happy Birthday", "Biết đếm 3 phách và quạt bài 3/4.", [
      warm(),
      {"minutes": 4, "title": "Nhịp 3/4 là gì?", "visual": {"type": "strum", "pattern": "D.D.D."}, "text": "Một số bài đếm 1 2 3 thay vì 1 2 3 4 (điệu valse). Nhấn mạnh phách 1, hai phách sau nhẹ hơn: MẠNH – nhẹ – nhẹ."},
      song("05_happy_birthday.cho", 10, "Happy Birthday", "Bài đổi hợp âm ở chữ \"birthday\" và \"you\". Bắt đầu hát ở phách 3 (\"Happy\") trước hợp âm G.", "D.D.D."),
      changes("G", "D", 4, "Một vòng giữ tay."),
      review()]),
    session("w4s5", "Ôn tuần 4 & kiểm tra", "Đạt các mục kiểm tra để mở tuần 5.", [
      warm(),
      changes("Em", "C", 5, "Mục tiêu 20 lần."),
      rhythm("Kiểm tra: chấm mẫu Ballad", 4, 70, "BALLAD", "Chấm 8 ô nhịp mẫu Ballad ở 70 BPM. Chỗ \"·\" tay vẫn đưa nhưng không chạm dây — chạm vào đó sẽ bị tính là cú thừa."),
      song("01_vong_4_hop_am.cho", 6, "Kiểm tra: vòng 8 lần với Ballad", "Đặt 70 BPM, chạy 8 vòng G–D–Em–C không dừng nhịp.", "D.DU.UDU"),
      song("04_twinkle.cho", 6, "Kiểm tra: Twinkle trọn bài", "Đàn hết bài không dừng — sai hợp âm cũng được, miễn tay phải không dừng."),
      review("Đã đạt thì vào thẻ Tuần 4 để đánh dấu các mục tự kiểm tra.")]),
  ]})

# ---------------- Week 5 ----------------
weeks.append({"number": 5, "title": "Ghép hát · A và E",
  "goal": "Vừa đàn vừa hát — kỹ năng cốt lõi của đệm hát — và thêm hai hợp âm A, E.",
  "test": [
    {"type": "chord", "chord": "A", "text": "A được micro chấm đúng 3 lần liền"},
    {"type": "chord", "chord": "E", "text": "E được micro chấm đúng 3 lần liền"},
    {"type": "changes", "chord": "D", "chordB": "A", "min": 20, "text": "Chuyển D ↔ A: ít nhất 20 lần trong 1 phút"},
    {"type": "self", "text": "Vừa đàn vừa hát trọn 1 bài mẫu ở tốc độ chậm"}],
  "sessions": [
    session("w5s1", "Cách ghép hát", "Bốn bước ghép giọng hát vào tiếng đàn.", [
      warm(),
      {"minutes": 4, "title": "Bốn bước", "text": "1) Đàn trọn bài, không hát. 2) Đàn và đọc lời theo nhịp (không cần giai điệu). 3) Mỗi hợp âm chỉ quạt MỘT cú ở phách 1, rồi hát — tay chỉ cần đúng lúc đổi. 4) Quạt đủ mẫu và hát. Kẹt ở bước nào thì quay lại bước trước."},
      song("06_kumbaya.cho", 12, "Kumbaya — ghép hát", "Bài chậm, lời lặp lại, rất hợp để tập. Làm lần lượt 4 bước trên điệp khúc. Đổi tông ở nút Tông nếu bài cao/thấp so với giọng bạn.", "D.DU.UDU"),
      changes("C", "Am", 4, "Kumbaya có Am–D; ôn Am trước."),
      review("Bước nào làm được? Không sao nếu hôm nay mới tới bước 2.")]),
    session("w5s2", "Hợp âm A", "Ba ngón xếp hàng ở ngăn 2.", [
      warm(), fingers(3, 70),
      chord("A", 8, "Trỏ dây 4, giữa dây 3, áp út dây 2, cả ba ở ngăn 2 — xếp chéo cho vừa ô. Dây 1 buông phải kêu. Gảy từ dây 5 (A — bass)."),
      changes("D", "A", 7, "Cả hai nằm quanh ngăn 2. Nhấc cả bàn tay, đặt ba ngón của A cùng lúc như đóng con dấu. Nhớ đổi chỗ gảy: D từ dây 4, A từ dây 5.", {"type": "chord", "chord": "A"}),
      metro("Quạt A – D", 5, 70, "BALLAD", "A 1 ô – D 1 ô với mẫu Ballad."),
      review()]),
    session("w5s3", "Hợp âm E", "E là Em thêm một ngón; E → Am cùng hình.", [
      warm(),
      chord("E", 7, "Bấm Em rồi thêm ngón trỏ ngăn 1 dây 3 (G#). Quạt cả 6 dây, bass là dây 6 buông."),
      changes("E", "Am", 7, "Mẹo hay nhất tuần: hình E dời xuống một dây chính là Am — ba ngón giữ nguyên hình, chỉ trượt cả bàn tay.", {"type": "fretboard", "frets": 3, "dots": [
          {"s": 3, "f": 1, "label": "1"}, {"s": 5, "f": 2, "label": "2"}, {"s": 4, "f": 2, "label": "3"}], "caption": "E: ngón 1 dây 3, ngón 2 dây 5, ngón 3 dây 4. Dời tất cả xuống một dây (về phía dây 1) là được Am."}),
      metro("E – Am – A", 5, 70, "BALLAD", "Mỗi hợp âm 1 ô nhịp."),
      review()]),
    session("w5s4", "Amazing Grace 3/4", "Đệm hát bài 3/4 với G7.", [
      warm(),
      chord("G7", 5, "Như G nhưng dây 1 hạ về ngăn 1 (ngón trỏ); hai dây bass dùng ngón áp út (dây 6) và ngón giữa (dây 5). G7 nghe như \"muốn đi tới\" C."),
      song("07_amazing_grace.cho", 12, "Amazing Grace", "Bài 3/4: đếm 1 2 3. Dùng 4 bước ghép hát như buổi 1. Hạ tông nếu cao quá (nút Tông −).", "D.DUDU"),
      changes("G", "C", 4, "G–C xuất hiện nhiều trong bài."),
      review()]),
    session("w5s5", "Ôn tuần 5 & kiểm tra", "Đạt các mục kiểm tra để mở tuần 6.", [
      warm(),
      chord("A", 3, "3 lần liền đúng."), chord("E", 3, "3 lần liền đúng."),
      changes("D", "A", 5, "Mục tiêu 20 lần."),
      song("06_kumbaya.cho", 8, "Kiểm tra: đàn và hát trọn bài", "Chọn Kumbaya hoặc một bài mẫu bạn thích. Tốc độ chậm cũng được — miễn hát và đàn liền từ đầu đến cuối.", "D.DU.UDU"),
      {"minutes": 4, "title": "Ghi âm lần đầu", "tool": "RECORD", "text": "Ghi lại một lượt đàn hát, nghe lại ngay. Đừng ngại — ai nghe giọng mình lần đầu cũng thấy lạ. Chấm thử 5 tiêu chí để biết tuần sau tập gì."},
      review()]),
  ]})

# ---------------- Week 6 ----------------
weeks.append({"number": 6, "title": "Dm, F rút gọn, Bm7 · capo & tông",
  "goal": "Ba hợp âm mở rộng, và biết dùng capo + chuyển tông để bài vừa với giọng mà vẫn bấm thế dễ.",
  "test": [
    {"type": "chord", "chord": "Dm", "text": "Dm được micro chấm đúng 3 lần liền"},
    {"type": "chord", "chord": "F", "text": "F (rút gọn) được micro chấm đúng 3 lần liền"},
    {"type": "changes", "chord": "C", "chordB": "F", "min": 15, "text": "Chuyển C ↔ F: ít nhất 15 lần trong 1 phút"},
    {"type": "self", "text": "Tìm được tông hợp giọng cho 1 bài bằng nút Tông và capo"}],
  "sessions": [
    session("w6s1", "Hợp âm Dm", "Dm và cặp Dm ↔ Am.", [
      warm(), fingers(3, 70),
      chord("Dm", 8, "Giữa ngăn 2 dây 3, áp út ngăn 3 dây 2, trỏ ngăn 1 dây 1. Gảy từ dây 4. So với D: chỉ dây 1 đổi từ ngăn 2 xuống ngăn 1 — nghe buồn hơn hẳn (thứ)."),
      changes("Dm", "Am", 7, "Hai hợp âm thứ hay đi cùng nhau trong nhạc Việt.", {"type": "chord", "chord": "Dm"}),
      metro("Am – Dm – E", 5, 70, "BALLAD", "Vòng thứ cổ điển: Am 1 ô, Dm 1 ô, E 1 ô, Am 1 ô."),
      review()]),
    session("w6s2", "F rút gọn", "Chặn 2 dây bằng ngón trỏ — bước đầu của hợp âm chặn.", [
      warm(),
      {"minutes": 5, "title": "Ngón trỏ đè 2 dây", "visual": {"type": "press"}, "text": "Ngón trỏ nằm phẳng, đè cả dây 1 và dây 2 ở ngăn 1, sát thanh phím. Lăn ngón hơi nghiêng về phía cạnh xương (phía ngón cái) — cạnh cứng hơn bụng ngón nên ít rè. Gảy từng dây để kiểm tra."},
      chord("F", 7, "Thêm giữa ngăn 2 dây 3 và áp út ngăn 3 dây 4. Chỉ gảy 4 dây mỏng, bass F ở dây 4."),
      changes("C", "F", 6, "Ngón áp út và ngón giữa của C cùng dời sang một dây về phía dây mỏng (vẫn ngăn 3 và 2); ngón trỏ duỗi phẳng đè 2 dây ở ngăn 1.", {"type": "chord", "chord": "F"}),
      review("Đau đầu ngón trỏ là bình thường — F cần vài tuần mới sạch.")]),
    session("w6s3", "Bm7 và vòng Đô", "Bm7 thay cho Bm chặn; vòng C–Am–Dm–G.", [
      warm(),
      chord("Bm7", 6, "Ngón trỏ đè ngăn 2 từ dây 5 tới dây 1, giữa ngăn 3 dây 2, áp út ngăn 4 dây 4. Gảy từ dây 5. Bm7 dùng tạm ở mọi chỗ ghi Bm cho tới khi bạn học hợp âm chặn."),
      changes("G", "Bm7", 5, "Hay gặp trong vòng G–Bm–C–D.", {"type": "chord", "chord": "Bm7"}),
      song("02_vong_slow_C.cho", 10, "Vòng Đô", "C–Am–Dm–G — vòng của rất nhiều bài Việt. 60 BPM, mẫu Ballad.", "D.DU.UDU"),
      review()]),
    session("w6s4", "Capo và chọn tông", "Hiểu capo; tìm tông hợp giọng.", [
      warm(),
      {"minutes": 5, "title": "Capo làm gì?", "visual": {"type": "capo", "capo": 2}, "text": "Capo kẹp ngang cần đàn, thay cho đầu đàn. Capo ngăn 2 + thế G → nghe ra tông A. Bạn vẫn bấm hình quen thuộc, chỉ là cao hơn. Trong màn bài hát, hợp âm hiển thị luôn là THẾ BẤM."},
      {"minutes": 5, "title": "Tìm tông hợp giọng", "text": "Hát câu cao nhất của bài (thường ở điệp khúc). Căng giọng → bấm Tông − ; thấy trầm, hụt hơi → Tông +. Khi đã vừa giọng, nếu hợp âm khó app sẽ gợi ý \"Dễ hơn: capo X\" — bấm Dùng."},
      song("06_kumbaya.cho", 10, "Thử với Kumbaya", "Đổi tông lên/xuống vài nấc, mỗi lần hát thử điệp khúc. Chọn tông thoải mái nhất, rồi để app gợi ý capo.", None),
      changes("C", "F", 3, "Một vòng giữ F."),
      review()]),
    session("w6s5", "Ôn tuần 6 & kiểm tra", "Đạt các mục kiểm tra để mở tuần 7.", [
      warm(),
      chord("Dm", 3, "3 lần liền đúng."), chord("F", 4, "3 lần liền đúng — chỉ gảy 4 dây mỏng."),
      changes("C", "F", 6, "Mục tiêu 15 lần."),
      song("02_vong_slow_C.cho", 7, "Vòng Đô trọn vẹn", "4 vòng liền, 60 BPM.", "D.DU.UDU"),
      review()]),
  ]})

# ---------------- Week 7 ----------------
weeks.append({"number": 7, "title": "Bass – quạt, nhấn phách, intro/outro",
  "goal": "Đệm điệu Slow/Ballad kiểu bass–quạt, nhấn đúng phách, và biết mở đầu/kết thúc bài cho trọn vẹn.",
  "test": [
    {"type": "quiz", "min": 9, "text": "Đố nốt: đúng ít nhất 9/10"},
    {"type": "self", "text": "Gảy đúng nốt bass của C, G, Am, D, Em mà không nhìn hình"},
    {"type": "self", "text": "Đàn vòng Đô với kiểu bass–quạt 4 vòng liền"},
    {"type": "self", "text": "Có intro và outro cho 1 bài mẫu"}],
  "sessions": [
    session("w7s1", "Bass – quạt", "Mẫu B . D U . U D U: thay cú quạt đầu bằng nốt bass.", [
      warm(),
      {"minutes": 5, "title": "Ôn nốt bass", "tool": "QUIZ", "visual": {"type": "fretboard", "frets": 3, "dots": BASS_DOTS}, "text": "Chạm từng chấm để nghe nốt bass của 5 hợp âm. Sau đó làm một lượt đố nốt."},
      {"minutes": 8, "title": "Mẫu bass – quạt", "tool": "METRONOME", "bpm": 60, "pattern": "BALLAD", "visual": {"type": "strum", "pattern": "B.DU.UDU"}, "text": "Giống mẫu Ballad, nhưng ở phách 1 dùng phím gảy (hoặc ngón cái) gảy riêng nốt bass của hợp âm, rồi quạt phần còn lại. Bắt đầu với C (bass dây 5) và G (bass dây 6)."},
      song("02_vong_slow_C.cho", 8, "Vòng Đô bass – quạt", "Am bass dây 5 buông, Dm bass dây 4 buông.", "B.DU.UDU"),
      review()]),
    session("w7s2", "Bass luân phiên", "Bass 1 – bass 2 cho mỗi hợp âm.", [
      warm(),
      {"minutes": 6, "title": "Hai nốt bass của C và G", "tool": "NOTES", "visual": {"type": "fretboard", "frets": 3, "dots": [
          {"s": 5, "f": 3, "label": "1", "root": True}, {"s": 4, "f": 2, "label": "2"}, {"s": 6, "f": 3, "label": "1", "root": True}, {"s": 4, "f": 0, "label": "2"}],
          "caption": "C: bass 1 = dây 5 ngăn 3 (C), bass 2 = dây 4 ngăn 2 (E). G: bass 1 = dây 6 ngăn 3 (G), bass 2 = dây 4 buông (D)."},
       "text": "Điệu Slow hay dùng hai nốt bass: phách 1 gảy bass chính, phách 3 gảy bass phụ ở dây gần đó. Không cần đổi thế tay trái — các nốt đó đã nằm trong hình hợp âm."},
      metro("B . D U B U D U", 8, 60, "BALLAD", "Phách 1 bass 1, phách 3 bass 2, các chỗ khác quạt nhẹ. Tập trên C rồi G, sau đó chuyển C–G."),
      changes("C", "G", 4, "Một vòng giữ tay."),
      review()]),
    session("w7s3", "Nhấn phách", "Làm tiếng đàn có sức sống: nhấn và tắt tiếng.", [
      warm(),
      metro("Nhấn phách 2 và 4", 6, 70, "DOWN_UP", "Trong DU đều, quạt mạnh hơn ở phách 2 và 4 — kiểu nhạc pop, nghe \"nảy\" hơn hẳn."),
      metro("Chặn tiếng (chách)", 6, 70, "BALLAD", "Ở phách 3, áp lòng bàn tay phải lên dây ngay sau khi quạt để tiếng tắt \"chách\". Đây là kiểu đệm Slow Rock/ Pop rất hay gặp."),
      song("08_auld_lang_syne.cho", 8, "Auld Lang Syne", "Áp dụng nhấn phách hoặc bass–quạt cho bài này.", "B.DU.UDU"),
      review()]),
    session("w7s4", "Intro và outro", "Mở đầu và kết bài cho trọn vẹn.", [
      warm(),
      {"minutes": 5, "title": "Intro đơn giản", "text": "Cách dễ nhất: đàn một vòng hợp âm của câu đầu (hoặc 2 ô nhịp cuối bài) trước khi hát. Đếm to \"1 2 3 4\" trong đầu để vào đúng phách — người nghe cũng biết nhịp."},
      {"minutes": 5, "title": "Outro đơn giản", "text": "Kết bằng hợp âm chủ (thường là hợp âm đầu tiên của bài): quạt chậm dần, rồi một cú quạt xuống ngân dài. Có thể rải từng dây từ thấp lên cao thay cho cú quạt cuối."},
      song("06_kumbaya.cho", 10, "Đệm trọn bài có intro/outro", "Intro: 2 ô G–C. Kết: G quạt chậm, ngân.", "B.DU.UDU"),
      review()]),
    session("w7s5", "Ôn tuần 7 & kiểm tra", "Đạt các mục kiểm tra để mở tuần 8.", [
      warm(),
      {"minutes": 5, "title": "Đố nốt", "tool": "QUIZ", "text": "Mục tiêu 9/10."},
      song("02_vong_slow_C.cho", 8, "Kiểm tra: vòng Đô bass – quạt", "4 vòng liền.", "B.DU.UDU"),
      song("08_auld_lang_syne.cho", 7, "Kiểm tra: intro + outro", "Một bài mẫu có mở đầu và kết.", None),
      review()]),
  ]})

# ---------------- Week 8 ----------------
weeks.append({"number": 8, "title": "Bài của chính bạn",
  "goal": "Chọn một bài bạn thích, nhập vào app, tập từng đoạn và đệm hát trọn bài.",
  "test": [
    {"type": "self", "text": "Đã nhập 1 bài của bạn vào tab Bài hát"},
    {"type": "self", "text": "Đệm hát trọn bài đó không dừng nhịp"},
    {"type": "rubric", "min": 2, "text": "Một bản ghi tự chấm \"ổn\" trở lên ở cả 5 tiêu chí: nhịp đều · âm sạch · đổi hợp âm kịp · giọng hợp tông · liền mạch"}],
  "sessions": [
    session("w8s1", "Chọn và nhập bài", "Một bài ≤ 5 hợp âm, tốc độ vừa phải.", [
      warm(),
      {"minutes": 5, "title": "Chọn bài", "text": "Chọn bài bạn thuộc lời, chậm hoặc vừa, ít hợp âm (lý tưởng: các hợp âm bạn đã học). Tìm lời có hợp âm trên mạng — dạng [Am] trong lời hoặc dòng hợp âm trên dòng lời đều được."},
      {"minutes": 10, "title": "Nhập bài", "tool": "SONG", "text": "Vào tab Bài hát → Thêm bài, dán lời có hợp âm. Kiểm tra dòng \"Đọc được … hợp âm\". Mở bài, chỉnh Tông cho hợp giọng, dùng gợi ý capo nếu có hợp âm chặn."},
      {"minutes": 5, "title": "Xem các hợp âm của bài", "text": "Dải sơ đồ ở đầu bài cho biết bài cần những thế nào. Chạm vào từng hợp âm để xem cách bấm. Hợp âm nào mới thì luyện riêng bằng Kiểm tra hợp âm."},
      review()]),
    session("w8s2", "Tập từng đoạn", "Tách bài thành đoạn, mỗi đoạn chạy được mới ghép.", [
      warm(),
      {"minutes": 8, "title": "Đoạn 1 (lời 1)", "tool": "SONG", "text": "Chỉ đàn đoạn đầu, chậm (tự cuộn 0.6×), không hát. Lặp tới khi đổi hợp âm kịp."},
      {"minutes": 8, "title": "Điệp khúc", "tool": "SONG", "text": "Tương tự với điệp khúc. Điệp khúc thường cao hơn — thử hát nhẩm để kiểm tra tông."},
      {"minutes": 4, "title": "Ghép hai đoạn", "tool": "SONG", "text": "Đàn liền lời 1 → điệp khúc."},
      review()]),
    session("w8s3", "Chỗ đổi hợp âm khó", "Luyện riêng cặp hợp âm khó nhất của bài.", [
      warm(),
      {"minutes": 10, "title": "Chuyển hợp âm 1 phút", "tool": "CHANGES", "chord": "C", "chordB": "G", "text": "Chọn ở trên màn hình đúng cặp hợp âm bạn hay chậm nhất trong bài. Ba vòng 1 phút, ghi kỷ lục."},
      {"minutes": 8, "title": "Đoạn có chỗ khó", "tool": "SONG", "text": "Quay lại bài, đàn đoạn chứa cặp đó. Mẹo: ở ô nhịp trước khi đổi, quạt nhẹ cú cuối để có thời gian chuyển."},
      review()]),
    session("w8s4", "Ghép hát cả bài", "Đàn và hát trọn bài ở tốc độ chậm.", [
      warm(),
      {"minutes": 6, "title": "Bốn bước ghép hát", "tool": "SONG", "text": "Nhắc lại: đàn → đọc lời → 1 cú quạt mỗi hợp âm + hát → quạt đủ + hát."},
      {"minutes": 12, "title": "Đệm hát trọn bài", "tool": "SONG", "text": "Bật tự cuộn ở tốc độ bạn đã chạy được, thêm intro và outro như tuần 7."},
      review()]),
    session("w8s5", "Tổng duyệt", "Đệm hát trọn bài và tự chấm 5 tiêu chí.", [
      warm(),
      {"minutes": 4, "title": "Năm tiêu chí", "text": "Nhịp đều (không dừng tay phải) · Âm sạch (không rè/câm) · Đổi hợp âm kịp · Giọng hợp tông · Liền mạch từ intro tới outro."},
      {"minutes": 6, "title": "Chạy thử", "tool": "SONG", "text": "Đàn hát một lượt để làm nóng — chưa ghi."},
      {"minutes": 8, "title": "Biểu diễn & ghi âm", "tool": "RECORD", "text": "Đặt điện thoại cách đàn khoảng 1 m, bấm ghi, đàn hát trọn bài có intro và outro. Nghe lại, chấm 5 tiêu chí (Chưa / Ổn / Tốt). Chưa ổn tiêu chí nào thì tập riêng chỗ đó rồi ghi lại."},
      review("Chúc mừng — bạn đã đệm hát được một bài! Mở tab Tiến độ để nhìn lại 8 tuần.")]),
  ]})

data = {"weeks": weeks}
out = sys.argv[1]
with open(out, "w", encoding="utf-8", newline="\n") as f:
    json.dump(data, f, ensure_ascii=False, indent=1)
for w in weeks:
    print(w["number"], [sum(st["minutes"] for st in s["steps"]) for s in w["sessions"]])
