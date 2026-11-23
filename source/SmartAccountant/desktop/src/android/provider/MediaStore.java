package android.provider;
public final class MediaStore {
    public static final String ACTION_IMAGE_CAPTURE = "android.media.action.IMAGE_CAPTURE", EXTRA_OUTPUT = "output";
    public static final class Images { public static final class Media { public static final android.net.Uri EXTERNAL_CONTENT_URI = android.net.Uri.parse("content://media/external/images/media"); } }
    public static final class MediaColumns { public static final String DISPLAY_NAME = "_display_name", MIME_TYPE = "mime_type", RELATIVE_PATH = "relative_path"; }
}
