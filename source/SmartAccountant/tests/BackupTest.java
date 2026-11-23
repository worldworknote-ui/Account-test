import com.smart.hawala.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.security.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.util.zip.*;

public class BackupTest {
  static int fails = 0;
  static void check(boolean c, String m) { System.out.println((c ? "  PASS " : "  FAIL ") + m); if (!c) fails++; }

  static class FakeSource implements BackupFormat.Source {
    int nTx; int nImg; Random rnd = new Random(42); boolean badImage;
    FakeSource(int nTx, int nImg) { this.nTx = nTx; this.nImg = nImg; }
    public Map<String,Long> counts() { Map<String,Long> m = new LinkedHashMap<>(); m.put("offices", 2L); m.put("tx", (long) nTx); m.put("attachments", (long) nImg); return m; }
    public void rows(String t, BackupFormat.RowSink s) throws Exception {
      if (t.equals("currencies")) { s.row(new JSONObject().put("code","USD").put("name","دولار").put("decimals",2).put("active",1)); s.row(new JSONObject().put("code","SYP").put("name","ليرة").put("decimals",0).put("active",1)); }
      if (t.equals("offices")) { for (int i=1;i<=2;i++) s.row(new JSONObject().put("id",i).put("name","مكتب "+i).put("active",1).put("usd_limit_minor", JSONObject.NULL)); }
      if (t.equals("office_currencies")) { s.row(new JSONObject().put("office_id",1).put("code","USD")); s.row(new JSONObject().put("office_id",2).put("code","USD")); }
      if (t.equals("tx")) for (int i=1;i<=nTx;i++) s.row(new JSONObject().put("id",i).put("uuid","u-"+i).put("office_id", 1 + (i%2)).put("type","مدين / لنا").put("person","شخص "+i).put("amount_minor", 1000L*i).put("currency","USD").put("fee_type","بدون أجرة").put("fee_minor",0).put("created_at", 1000L+i).put("status", i%10==0?"CANCELLED":"ACTIVE"));
      if (t.equals("journal_entries")) for (int i=1;i<=nTx;i++) s.row(new JSONObject().put("id",i).put("tx_id",i).put("kind","PRINCIPAL").put("created_at",1000L+i));
      if (t.equals("journal_lines")) for (int i=1;i<=nTx;i++) { s.row(new JSONObject().put("id",2*i-1).put("entry_id",i).put("account","OFFICE").put("office_id",1+(i%2)).put("currency","USD").put("debit_minor",1000L*i).put("credit_minor",0)); s.row(new JSONObject().put("id",2*i).put("entry_id",i).put("account","SETTLEMENT").put("office_id",JSONObject.NULL).put("currency","USD").put("debit_minor",0).put("credit_minor",1000L*i)); }
      if (t.equals("settings")) s.row(new JSONObject().put("k","welcome").put("v","أهلًا"));
    }
    public List<BackupFormat.AttachmentRef> attachments() { List<BackupFormat.AttachmentRef> l = new ArrayList<>(); for (int i=1;i<=nImg;i++){ BackupFormat.AttachmentRef r=new BackupFormat.AttachmentRef(); r.id=i; r.txId=1+(i%Math.max(1,nTx)); r.mime="image/jpeg"; r.createdAt=5; l.add(r);} return l; }
    public byte[] attachmentData(BackupFormat.AttachmentRef r) { if (badImage && r.id==2) return null; byte[] b = new byte[300000 + (int)r.id*1000]; new Random(r.id).nextBytes(b); return b; }
    public List<String> warnings() { return new ArrayList<>(); }
  }
  static class MemStore implements BackupFormat.AttachmentStore {
    Map<Object, byte[]> m = new HashMap<>(); int discarded;
    public Object store(long id, InputStream in) throws Exception { ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n; while((n=in.read(b))>0) o.write(b,0,n); Object h = new Object(); m.put(h, o.toByteArray()); return h; }
    public void discard(Object h) { m.remove(h); discarded++; }
  }

  static byte[] makeBackup(BackupFormat.Source src, char[] pw) throws Exception {
    ByteArrayOutputStream file = new ByteArrayOutputStream();
    SecureStreams.EncryptingOutputStream enc = new SecureStreams.EncryptingOutputStream(file, pw, 100000, 64*1024);
    BackupFormat.write(src, enc, "5.0", 5);
    enc.close();
    return file.toByteArray();
  }
  static BackupFormat.Parsed read(byte[] f, char[] pw, MemStore st) throws Exception {
    return BackupFormat.readV5(new SecureStreams.DecryptingInputStream(new ByteArrayInputStream(f), pw), st);
  }

  public static void main(String[] a) throws Exception {
    char[] pw = "كلمة-سر-قوية 2026".toCharArray();
    char[] pwCopy = pw.clone();

    System.out.println("1) round trip with 50 tx and 6 images (multi-chunk)");
    byte[] f = makeBackup(new FakeSource(50, 6), pw);
    check(Arrays.equals(pw, pwCopy), "caller password array NOT wiped by encryption (the reported 4.x bug)");
    MemStore st = new MemStore();
    BackupFormat.Parsed p = read(f, pw, st);
    check(Arrays.equals(pw, pwCopy), "caller password array NOT wiped by decryption");
    check(p.table("tx").size() == 50, "50 tx rows restored");
    check(p.attachments.size() == 6, "6 attachments restored");
    check(st.m.size() == 6, "6 attachments staged");
    check(p.table("offices").get(0).getString("name").equals("مكتب 1"), "Arabic text intact");
    BackupFormat.Report r = BackupFormat.validate(p);
    check(r.ok(), "validation passes: " + r.errors);
    check(r.txActive == 45 && r.txCancelled == 5, "active/cancelled counts " + r.txActive + "/" + r.txCancelled);
    System.out.println("   file size = " + f.length + " bytes");

    System.out.println("2) wrong password");
    try { read(f, "wrong-password".toCharArray(), new MemStore()); check(false, "should fail"); }
    catch (SecureStreams.WrongPasswordException e) { check(true, "WrongPasswordException: " + e.getMessage().substring(0, 20)); }

    System.out.println("3) truncated file (cut in the middle and cut exactly before final chunk)");
    for (int cut : new int[]{ f.length / 2, f.length - 1, 200 }) {
      MemStore s3 = new MemStore();
      try { read(Arrays.copyOf(f, cut), pw, s3); check(false, "truncated at " + cut + " should fail"); }
      catch (IOException e) { check(e instanceof SecureStreams.CorruptBackupException, "cut@" + cut + " -> " + e.getMessage()); check(s3.m.isEmpty(), "  staged images cleaned up (" + s3.m.size() + ")"); }
    }

    System.out.println("4) one byte flipped in the middle");
    byte[] g = f.clone(); g[g.length / 2] ^= 0x01;
    try { read(g, pw, new MemStore()); check(false, "tampered should fail"); }
    catch (IOException e) { check(e instanceof SecureStreams.CorruptBackupException, "tamper -> " + e.getMessage()); }

    System.out.println("5) extra bytes appended");
    byte[] h = Arrays.copyOf(f, f.length + 5);
    try { read(h, pw, new MemStore()); check(false, "trailing data should fail"); }
    catch (IOException e) { check(true, "trailing -> " + e.getMessage()); }

    System.out.println("6) empty database backup");
    byte[] e0 = makeBackup(new FakeSource(0, 0), pw);
    BackupFormat.Parsed p0 = read(e0, pw, new MemStore());
    check(BackupFormat.validate(p0).ok(), "empty backup validates");

    System.out.println("7) aborted writer produces rejected file");
    ByteArrayOutputStream bo = new ByteArrayOutputStream();
    SecureStreams.EncryptingOutputStream enc = new SecureStreams.EncryptingOutputStream(bo, pw, 100000, 64*1024);
    enc.write(new byte[200000]); enc.abort();
    try { BackupFormat.readV5(new SecureStreams.DecryptingInputStream(new ByteArrayInputStream(bo.toByteArray()), pw), new MemStore()); check(false, "aborted should fail"); }
    catch (IOException ex) { check(true, "aborted -> " + ex.getMessage()); }

    System.out.println("8) skipped bad image is reported");
    FakeSource bad = new FakeSource(5, 3); bad.badImage = true;
    BackupFormat.Parsed pb = read(makeBackup(bad, pw), pw, new MemStore());
    check(pb.attachments.size() == 2, "2 of 3 images kept");
    check(pb.warnings.toString().contains("تخطي"), "warning about skipped image present");

    System.out.println("9) validation catches broken data");
    BackupFormat.Parsed pv = read(f, pw, new MemStore());
    pv.table("journal_lines").get(0).put("debit_minor", 999);
    check(!BackupFormat.validate(pv).ok(), "unbalanced entry detected: " + BackupFormat.validate(pv).errors);
    BackupFormat.Parsed pv2 = read(f, pw, new MemStore());
    pv2.table("tx").get(3).put("office_id", 77);
    check(!BackupFormat.validate(pv2).ok(), "missing office reference detected");
    BackupFormat.Parsed pv3 = read(f, pw, new MemStore());
    pv3.table("tx").get(4).put("uuid", "u-1");
    check(!BackupFormat.validate(pv3).ok(), "duplicate uuid detected");

    System.out.println("10) read a 4.0.x (SMARTBK4) backup produced by the OLD algorithm");
    byte[] v4 = makeV4(pw);
    MemStore s4 = new MemStore();
    BackupFormat.Parsed p4 = BackupFormat.readV4(v4, pw, s4);
    check(p4.format == 4 && p4.table("tx").size() == 1, "v4 parsed, tx=" + p4.table("tx").size());
    check(p4.attachments.size() == 1 && s4.m.size() == 1, "v4 attachment staged");
    check(BackupFormat.validate(p4).ok(), "v4 validates: " + BackupFormat.validate(p4).errors);
    try { BackupFormat.readV4(v4, "bad".toCharArray(), new MemStore()); check(false, "v4 wrong pw"); } catch (IOException ex) { check(true, "v4 wrong pw -> " + ex.getMessage()); }

    System.out.println("11) large backup memory behaviour (40 images ~ 13MB) with 32MB heap");
    long t0 = System.currentTimeMillis();
    File tmp = File.createTempFile("bkp", ".sba");
    try (FileOutputStream fo = new FileOutputStream(tmp)) {
      SecureStreams.EncryptingOutputStream e2 = new SecureStreams.EncryptingOutputStream(new BufferedOutputStream(fo), pw);
      BackupFormat.write(new FakeSource(2000, 40), e2, "5.0", 5); e2.close(); }
    final long[] cnt = {0};
    BackupFormat.AttachmentStore counting = new BackupFormat.AttachmentStore() {
      public Object store(long id, InputStream in) throws Exception { byte[] b = new byte[8192]; int n; while((n=in.read(b))>0) cnt[0]+=n; return id; }
      public void discard(Object h) {} };
    try (InputStream fi = new BufferedInputStream(new FileInputStream(tmp))) {
      BackupFormat.Parsed pl = BackupFormat.readV5(new SecureStreams.DecryptingInputStream(fi, pw), counting);
      check(pl.attachments.size() == 40 && pl.table("tx").size() == 2000, "large backup verified, file=" + tmp.length() + " bytes, imgBytes=" + cnt[0] + ", " + (System.currentTimeMillis()-t0) + "ms (default 310k iterations x2)");
    }
    System.out.println("12) 8.0: large tables spill to an encrypted temp file (constant memory)");
    File spillDir = new File(tmp.getParentFile(), "spill-" + System.nanoTime());
    long oldSpill = BackupFormat.spillChars;
    BackupFormat.spillChars = 20000;
    try (InputStream fi = new BufferedInputStream(new FileInputStream(tmp))) {
      BackupFormat.Parsed ps = BackupFormat.readV5(new SecureStreams.DecryptingInputStream(fi, pw), counting, spillDir);
      check(ps.tables.get("tx") instanceof BackupFormat.SpilledRows && ps.tables.get("journal_lines") instanceof BackupFormat.SpilledRows, "tx and journal_lines spilled to disk");
      check(!(ps.tables.get("offices") instanceof BackupFormat.SpilledRows), "small tables stay in memory");
      check(ps.table("tx").size() == 2000 && ps.table("journal_lines").size() == 4000, "sizes kept: tx=" + ps.table("tx").size());
      BackupFormat.Report rs = BackupFormat.validate(ps);
      check(rs.ok() && rs.txActive + rs.txCancelled == 2000 && rs.entries == 2000, "spilled backup validates: " + rs.errors);
      long sum1 = 0, sum2 = 0; int n1 = 0;
      for (JSONObject t : ps.table("tx")) { sum1 += t.getLong("amount_minor"); n1++; }
      for (JSONObject t : ps.table("tx")) sum2 += t.getLong("amount_minor");
      check(n1 == 2000 && sum1 == sum2 && sum1 == 1000L * 2000 * 2001 / 2, "two passes give identical rows, sum=" + sum1);
      check(ps.table("tx").get(1).getString("uuid").equals("u-2") && ps.table("tx").get(1999).getString("person").equals("شخص 2000"), "get(i) reads the right row");
      File sf = ((BackupFormat.SpilledRows) ps.tables.get("tx")).file();
      byte[] raw = java.nio.file.Files.readAllBytes(sf.toPath());
      String asText = new String(raw, "UTF-8") + new String(raw, "ISO-8859-1");
      check(raw.length > 50000 && !asText.contains("u-1999") && !asText.contains("amount_minor") && !asText.contains("شخص"), "temp file is encrypted (no plaintext), " + raw.length + " bytes");
      BackupFormat.discardSpill(ps);
      check(!sf.exists(), "discardSpill deletes the temp files");
    } finally {
      BackupFormat.spillChars = oldSpill;
      File[] left = spillDir.listFiles(); if (left != null) for (File x : left) x.delete(); spillDir.delete();
    }
    tmp.delete();

    // 10.14: نسخة بالإصدار 2 (رمز استرداد النسخ): تُفتح بكلمة المرور أو بالرمز، وتُرفض بغيرهما، وأي تلاعب يُكتشف
    {
      String code = "ABCD-EFGH-JKMN-PQRS-TUVW-XYZ2";
      byte[] rsalt = new byte[16]; new SecureRandom().nextBytes(rsalt);
      SecureStreams.RecoveryKey rk = SecureStreams.RecoveryKey.derive(code, rsalt, 100000);
      ByteArrayOutputStream bo2 = new ByteArrayOutputStream();
      SecureStreams.EncryptingOutputStream e3 = new SecureStreams.EncryptingOutputStream(bo2, "كلمة-النسخ".toCharArray(), 100000, 64 * 1024, rk);
      byte[] payload = new byte[200000]; new SecureRandom().nextBytes(payload);
      e3.write(payload); e3.close();
      byte[] file2 = bo2.toByteArray();
      check(readAll(file2, "كلمة-النسخ".toCharArray()).length == payload.length, "v2: تُفتح بكلمة المرور");
      check(java.util.Arrays.equals(readAll(file2, "abcd efgh jkmn pqrs tuvw xyz2".toCharArray()), payload), "v2: تُفتح برمز الاسترداد بأي شكل كُتب (أحرف صغيرة ومسافات)");
      boolean wrong = false;
      try { readAll(file2, "ABCD-EFGH-JKMN-PQRS-TUVW-XYZ3".toCharArray()); } catch (SecureStreams.WrongPasswordException w) { wrong = w.getMessage().contains("رمز استرداد"); }
      check(wrong, "v2: رمز خاطئ أو كلمة خاطئة تُرفض برسالة تذكر رمز الاسترداد");
      byte[] tampered = file2.clone(); tampered[60] ^= 1;
      boolean caught = false;
      try { readAll(tampered, "كلمة-النسخ".toCharArray()); } catch (java.io.IOException x) { caught = true; }
      check(caught, "v2: تعديل بايت في الرأس يُكتشف");
      ByteArrayOutputStream bo1 = new ByteArrayOutputStream();
      SecureStreams.EncryptingOutputStream e1 = new SecureStreams.EncryptingOutputStream(bo1, "كلمة-النسخ".toCharArray(), 100000, 64 * 1024, null);
      e1.write(payload); e1.close();
      byte[] v1 = bo1.toByteArray();
      check(v1[11] == 1 && file2[11] == 2, "بلا رمز تبقى النسخة بالإصدار 1 (تقرؤها الإصدارات الأقدم)");
    }

    System.out.println(fails == 0 ? "ALL TESTS PASSED" : (fails + " FAILURE(S)"));
    System.exit(fails == 0 ? 0 : 1);
  }

  static byte[] readAll(byte[] file, char[] pw) throws Exception {
    try (InputStream in = new SecureStreams.DecryptingInputStream(new ByteArrayInputStream(file), pw)) {
      ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
      while ((n = in.read(b)) > 0) o.write(b, 0, n);
      return o.toByteArray();
    }
  }

  /** Re-implementation of the 4.0.1 BackupManager.create() byte layout, to prove compatibility. */
  static byte[] makeV4(char[] pass) throws Exception {
    JSONObject root = new JSONObject(); root.put("schema", 4); root.put("createdAt", 123L); root.put("app","x"); root.put("appVersion","4.0");
    JSONArray tables = new JSONArray();
    String[][] data = {{"currencies","[{\"code\":\"USD\",\"name\":\"دولار\",\"decimals\":2,\"active\":1}]"},{"offices","[{\"id\":1,\"name\":\"مكتب\",\"active\":1}]"},{"office_currencies","[]"},
      {"tx","[{\"id\":1,\"uuid\":\"abc\",\"office_id\":1,\"type\":\"مدين / لنا\",\"amount_minor\":500,\"currency\":\"USD\",\"fee_type\":\"بدون أجرة\",\"fee_minor\":0,\"created_at\":1,\"status\":\"ACTIVE\"}]"},
      {"journal_entries","[{\"id\":1,\"tx_id\":1,\"kind\":\"PRINCIPAL\"}]"},{"journal_lines","[{\"id\":1,\"entry_id\":1,\"account\":\"OFFICE\",\"office_id\":1,\"currency\":\"USD\",\"debit_minor\":500,\"credit_minor\":0},{\"id\":2,\"entry_id\":1,\"account\":\"SETTLEMENT\",\"currency\":\"USD\",\"debit_minor\":0,\"credit_minor\":500}]"},
      {"opening_balances","[]"},{"exchange_rates","[]"},{"settings","[]"},{"backup_history","[]"},{"audit_logs","[]"}};
    for (String[] d : data) tables.put(new JSONObject().put("name", d[0]).put("rows", new JSONArray(d[1])));
    root.put("tables", tables);
    JSONArray at = new JSONArray(); at.put(new JSONObject().put("id",1).put("tx_id",1).put("mime","image/jpeg").put("created_at",2).put("data", Base64.getEncoder().encodeToString(new byte[]{1,2,3,4,5})));
    root.put("attachments", at); root.put("skippedAttachments", 0);
    ByteArrayOutputStream gz = new ByteArrayOutputStream(); try (GZIPOutputStream z = new GZIPOutputStream(gz)) { z.write(root.toString().getBytes("UTF-8")); }
    byte[] plain = gz.toByteArray(); byte[] salt = new byte[16]; byte[] iv = new byte[12]; new SecureRandom().nextBytes(salt); new SecureRandom().nextBytes(iv);
    byte[] MAGIC = "SMARTBK4".getBytes("US-ASCII");
    PBEKeySpec s = new PBEKeySpec(pass, salt, 310000, 256); byte[] k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(s).getEncoded();
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k,"AES"), new GCMParameterSpec(128, iv)); c.updateAAD(MAGIC); byte[] cipher = c.doFinal(plain);
    ByteArrayOutputStream o = new ByteArrayOutputStream(); o.write(MAGIC); DataOutputStream d = new DataOutputStream(o);
    d.writeInt(1); d.writeInt(310000); d.writeInt(16); d.write(salt); d.writeInt(12); d.write(iv); d.writeInt(cipher.length); d.write(cipher); d.flush();
    return o.toByteArray();
  }
}
