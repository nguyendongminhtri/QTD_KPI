package vn.quy.sync;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Đọc chứng từ từ SQL Server của quỹ (SP sp_Giao_Dich_LietKe_GetAll_ByDate),
 * tính KPI theo THÁNG DƯƠNG LỊCH cho từng tài khoản kế toán, rồi đẩy bảng KPI lên website.
 * Dữ liệu khách hàng KHÔNG rời khỏi máy quỹ.
 *
 * Chạy:  java -jar sync-kpi-1.0-all.jar                 (tháng hiện tại; 5 ngày đầu tháng tính thêm tháng trước)
 *        java -jar sync-kpi-1.0-all.jar ky 10/2026      (một tháng)
 *        java -jar sync-kpi-1.0-all.jar nam 2026        (cả năm: từ tháng 1 đến tháng hiện tại hoặc tháng 12)
 *
 * Biến môi trường: SYNC_DB_PWD, SYNC_TOKEN, SYNC_API  (bắt buộc; SYNC_API trỏ tới /api/sync/kpi-ky)
 *                  SYNC_DB_URL, SYNC_DB_USER, SYNC_CHUNK_DAYS (tùy chọn)
 */
public class SyncGiaoDich {

    static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    static final int GAP_PHUT = 30;          // cách nhau quá số phút này thì tính là lượt mới
    static final int NGAY_TINH_LAI_THANG_TRUOC = 5;

    // Nghiệp vụ tự động / dự thu / dự chi: không tính KPI
    static final Set<String> LOAI_NGHIEP_VU = Set.of(
            "Lãi nhập gốc", "Chuyển kỳ hạn mới", "Xuất ngoại bảng",
            "Dự thu tiền vay", "Dự chi tiết kiệm");

    // Bút toán nội bộ (theo đầu diễn giải): không tính KPI
    static final String[] LOAI_DIEN_GIAI = {
            "Trích khấu hao", "Phân bổ chi phí", "Tiếp quỹ", "PGD hoàn quỹ"};

    static String env(String key, String def) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            if (def != null) return def;
            throw new IllegalStateException("Thiếu biến môi trường: " + key);
        }
        return v;
    }

    static String iso(Timestamp t) {
        return t == null ? null : t.toLocalDateTime().toString();
    }

    public static void main(String[] args) throws Exception {
        String dbPwd = env("SYNC_DB_PWD", null);
        String token = env("SYNC_TOKEN", null);
        String api = env("SYNC_API", null);
        String dbUser = env("SYNC_DB_USER", "sync_reader");
        String dbUrl = env("SYNC_DB_URL",
                "jdbc:sqlserver://localhost\\SQLEXPRESS;databaseName=ITDVAPCF;"
                        + "encrypt=true;trustServerCertificate=true");
        int chunkDays = Integer.parseInt(env("SYNC_CHUNK_DAYS", "7"));

        LocalDate today = LocalDate.now();
        List<YearMonth> cacKy = new ArrayList<>();

        if (args.length == 2 && args[0].equals("ky")) {
            cacKy.add(YearMonth.parse(args[1], DateTimeFormatter.ofPattern("MM/yyyy")));
        } else if (args.length == 2 && args[0].equals("nam")) {
            int nam = Integer.parseInt(args[1]);
            if (nam > today.getYear()) throw new IllegalArgumentException("Năm " + nam + " chưa tới");
            int thangCuoi = (nam == today.getYear()) ? today.getMonthValue() : 12;
            for (int m = 1; m <= thangCuoi; m++) cacKy.add(YearMonth.of(nam, m));
        } else {
            YearMonth hienTai = YearMonth.from(today);
            if (today.getDayOfMonth() <= NGAY_TINH_LAI_THANG_TRUOC) cacKy.add(hienTai.minusMonths(1));
            cacKy.add(hienTai);
        }

        ObjectMapper mapper = new ObjectMapper();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

        // Tổng hợp để in cuối cùng: tháng -> tài khoản -> số lượt
        Map<YearMonth, List<Map<String, Object>>> tongHop = new LinkedHashMap<>();

        for (YearMonth ym : cacKy) {
            LocalDate tu = ym.atDay(1);
            LocalDate cuoiKy = ym.atEndOfMonth();
            LocalDate den = cuoiKy.isAfter(today) ? today : cuoiKy;   // tháng đang chạy dở

            // Đọc cả tháng (chia đoạn để SP không quá tải), gom lại rồi tính một lần
            List<Map<String, Object>> rows = new ArrayList<>();
            for (LocalDate s = tu; !s.isAfter(den); s = s.plusDays(chunkDays)) {
                LocalDate e = s.plusDays(chunkDays - 1);
                if (e.isAfter(den)) e = den;
                rows.addAll(readRows(dbUrl, dbUser, dbPwd, s, e));
            }

            List<Map<String, Object>> kpi = tinhKpiKy(rows, tu, den);
            pushKpiKy(http, mapper, api, token, ym.toString(), tu, cuoiKy, kpi);
            tongHop.put(ym, kpi);

            System.out.println(LocalDateTime.now().withNano(0) + " OK kỳ " + ym + " ("
                    + tu.format(DMY) + " -> " + cuoiKy.format(DMY) + "): "
                    + rows.size() + " chứng từ, " + kpi.size() + " tài khoản");
        }

        // In bảng tổng hợp ra log để đối chiếu
        System.out.println("=== Tổng hợp số lượt ===");
        for (Map.Entry<YearMonth, List<Map<String, Object>>> e : tongHop.entrySet()) {
            StringBuilder sb = new StringBuilder("  " + e.getKey() + ":");
            for (Map<String, Object> m : e.getValue()) {
                sb.append(' ').append(m.get("nguoiTao")).append('=').append(m.get("soLuot"))
                        .append('(').append(m.get("soKhach")).append(" khách)");
            }
            System.out.println(sb);
        }
    }

    /** Đọc chứng từ từ SQL Server theo khoảng ngày chứng từ (cột JDBC đánh số từ 1). */
    static List<Map<String, Object>> readRows(String dbUrl, String dbUser, String dbPwd,
                                              LocalDate tu, LocalDate den) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        String sql = "SET NOCOUNT ON; SET DATEFORMAT dmy; "
                + "EXEC sp_Giao_Dich_LietKe_GetAll_ByDate @tu_ngay = ?, @toi_ngay = ?";
        try (Connection c = DriverManager.getConnection(dbUrl, dbUser, dbPwd);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, tu.format(DMY));
            ps.setString(2, den.format(DMY));

            boolean has = ps.execute();
            while (true) {
                if (has) {
                    try (ResultSet rs = ps.getResultSet()) {
                        if (rs.getMetaData().getColumnCount() >= 24) {
                            while (rs.next()) {
                                Map<String, Object> m = new LinkedHashMap<>();
                                m.put("soCt", rs.getString(1));
                                Timestamp ngay = rs.getTimestamp(4);
                                m.put("ngay", ngay == null ? null : ngay.toLocalDateTime().toLocalDate().toString());
                                m.put("dienGiai", rs.getString(5));
                                m.put("nghiepVu", rs.getString(17));
                                m.put("trangThai", rs.getString(18));
                                m.put("nguoiTao", rs.getString(19));
                                m.put("ngayTao", iso(rs.getTimestamp(20)));
                                m.put("tenKh", rs.getString(24));
                                rows.add(m);
                            }
                        }
                    }
                } else if (ps.getUpdateCount() == -1) {
                    break;
                }
                has = ps.getMoreResults();
            }
        }
        return rows;
    }

    /** KPI của một tháng theo từng tài khoản: tổng lượt khách và số khách khác nhau trong tháng. */
    static List<Map<String, Object>> tinhKpiKy(List<Map<String, Object>> rows, LocalDate tu, LocalDate den) {
        record Item(String nguoiTao, String kh, LocalDateTime t) {}

        List<Item> items = new ArrayList<>();
        for (Map<String, Object> m : rows) {
            if (!"Đã duyệt".equals(m.get("trangThai"))) continue;           // bỏ chứng từ đang nhập
            String nv = (String) m.get("nghiepVu");
            if (nv == null || LOAI_NGHIEP_VU.contains(nv.trim())) continue;

            String dg = m.get("dienGiai") == null ? "" : ((String) m.get("dienGiai")).trim();
            boolean noiBo = false;
            for (String p : LOAI_DIEN_GIAI) {
                if (dg.startsWith(p)) { noiBo = true; break; }
            }
            if (noiBo) continue;

            String kh = m.get("tenKh") == null ? "" : ((String) m.get("tenKh")).trim();
            if (kh.isEmpty() || m.get("ngayTao") == null || m.get("nguoiTao") == null
                    || m.get("ngay") == null) continue;

            // Chứng từ thuộc tháng nào là theo NGÀY CHỨNG TỪ (khớp cách lọc của câu SQL đã đối chiếu)
            LocalDate ngayCt = LocalDate.parse((String) m.get("ngay"));
            if (ngayCt.isBefore(tu) || ngayCt.isAfter(den)) continue;

            items.add(new Item(((String) m.get("nguoiTao")).trim(), kh,
                    LocalDateTime.parse((String) m.get("ngayTao"))));
        }

        // Gom theo (người tạo, khách), sắp theo giờ tạo
        Map<String, List<Item>> nhom = new LinkedHashMap<>();
        for (Item it : items) {
            nhom.computeIfAbsent(it.nguoiTao() + "|" + it.kh(), k -> new ArrayList<>()).add(it);
        }

        Map<String, int[]> luot = new LinkedHashMap<>();
        Map<String, Set<String>> khach = new LinkedHashMap<>();
        for (List<Item> ds : nhom.values()) {
            ds.sort(Comparator.comparing(Item::t));
            Item truoc = null;
            for (Item it : ds) {
                boolean moi = truoc == null
                        || !truoc.t().toLocalDate().equals(it.t().toLocalDate())
                        || Duration.between(truoc.t(), it.t()).toMinutes() > GAP_PHUT;
                luot.computeIfAbsent(it.nguoiTao(), k -> new int[1]);
                khach.computeIfAbsent(it.nguoiTao(), k -> new HashSet<>()).add(it.kh());
                if (moi) luot.get(it.nguoiTao())[0]++;
                truoc = it;
            }
        }

        List<Map<String, Object>> kq = new ArrayList<>();
        for (Map.Entry<String, int[]> e : luot.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nguoiTao", e.getKey());
            m.put("soLuot", e.getValue()[0]);
            m.put("soKhach", khach.get(e.getKey()).size());
            kq.add(m);
        }
        kq.sort((a, b) -> Integer.compare((Integer) b.get("soLuot"), (Integer) a.get("soLuot")));
        return kq;
    }

    /** Gửi bảng KPI của một tháng lên website; website xóa dòng cũ của tháng rồi ghi lại. */
    static void pushKpiKy(HttpClient http, ObjectMapper mapper, String api, String token,
                          String ky, LocalDate tu, LocalDate den,
                          List<Map<String, Object>> kpi) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ky", ky);
        body.put("tu", tu.toString());
        body.put("den", den.toString());
        body.put("rows", kpi);

        HttpRequest req = HttpRequest.newBuilder(URI.create(api))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("X-Sync-Token", token)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("API lỗi " + resp.statusCode() + ": " + resp.body());
        }
    }
}