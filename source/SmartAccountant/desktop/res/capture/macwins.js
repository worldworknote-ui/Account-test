// 12.2 المحاسب الذكي — قائمة النوافذ الظاهرة على الماك (من الأمام للخلف) لزر «حدد النافذة» في إطار الالتقاط.
// يقرأ مكان كل نافذة وحجمها واسم برنامجها فقط (CGWindowListCopyWindowInfo)، ولا يحتاج إذن «إمكانية الوصول».
// المخرجات: سطر لكل نافذة: رقم البرنامج، x، y، العرض، الارتفاع، اسم البرنامج بـBase64.
ObjC.import('Foundation');
ObjC.import('CoreGraphics');
function run() {
    // 1 = الظاهرة فقط، 16 = بلا عناصر سطح المكتب
    var raw = $.CGWindowListCopyWindowInfo(1 | 16, 0);
    var list = ObjC.deepUnwrap(ObjC.castRefToObject(raw));
    if (!list) return '';
    var out = [];
    for (var i = 0; i < list.length; i++) {
        var d = list[i];
        if (!d || d.kCGWindowLayer !== 0 || !d.kCGWindowBounds) continue;
        var b = d.kCGWindowBounds;
        var name = String(d.kCGWindowOwnerName || '');
        var b64 = $(name).dataUsingEncoding($.NSUTF8StringEncoding).base64EncodedStringWithOptions(0).js;
        out.push([d.kCGWindowOwnerPID, Math.round(b.X), Math.round(b.Y), Math.round(b.Width), Math.round(b.Height), b64].join('\t'));
    }
    return out.join('\n');
}
