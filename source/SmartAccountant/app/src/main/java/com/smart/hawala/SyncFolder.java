package com.smart.hawala;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 10.0: ناقل المزامنة: مجلد مشترك (Google Drive أو OneDrive أو Dropbox على الماك والويندوز، ومجلد تزامنه أداة مثل Syncthing
 * على الهاتف). السحابة لا ترى إلا ملفات مشفّرة بأسماء معتمة. كل جهاز يكتب ملفاته فقط ولا يعدّل ملف غيره أبدًا.
 */
public interface SyncFolder {
    /** أسماء الملفات التي تبدأ بالبادئة. */
    List<String> list(String prefix) throws IOException;

    boolean exists(String name) throws IOException;

    /** null إن لم يوجد. */
    byte[] read(String name) throws IOException;

    /** كتابة ذرية: ملف مؤقت ثم إعادة تسمية، فلا يرى جهاز آخر ملفًا نصف مكتوب. */
    void write(String name, byte[] data) throws IOException;

    /** وصف للمستخدم (المسار أو اسم المجلد). */
    String describe();

    /** ملاحظة تشخيصية عن الجولة الأخيرة (مثل: المزوّد ما زال يحدّث القائمة)، أو فارغ. */
    default String note() { return ""; }

    /** 10.2: بعد فك مفتاح الخزنة (للنواقل التي تحتاج رمزًا مشتقًا منه، مثل خادم المزامنة). */
    default void unlock(SyncCrypto.Keys keys) {}

    /** 10.2: نوع الناقل للعرض: مجلد مشترك، Google Drive، خادم. */
    default String kind() { return "folder"; }

    /** 10.4: هل يدعم الناقل حذف الملفات (للتنظيف التلقائي)؟ */
    default boolean canDelete() { return false; }

    /** 10.4: يحذف ملفًا (لا خطأ إن لم يوجد). */
    default void delete(String name) throws IOException { throw new IOException("هذه الطريقة لا تدعم حذف الملفات"); }

    /** 10.4: حجم كل ملف ووقت تعديله [البايتات، ميلي ثانية]، أو null إن لم يدعمه الناقل. */
    default Map<String, long[]> stat(String prefix) throws IOException { return null; }
}
