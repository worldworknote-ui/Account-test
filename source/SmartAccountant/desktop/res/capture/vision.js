// 12.1 المحاسب الذكي — قراءة صورة ما تحت «إطار الالتقاط» على الماك بإطار Vision المدمج في النظام (بلا إنترنت).
// المخرجات: سطر لكل سطر نص: x، y، العرض، الارتفاع (نسبية 0..1، والأصل أسفل الصورة)، ثم النص بـBase64.
ObjC.import('Foundation');
ObjC.import('Vision');
function run(argv) {
    var url = $.NSURL.fileURLWithPath(argv[0]);
    function ocr(langs) {
        var req = $.VNRecognizeTextRequest.alloc.init;
        req.setRecognitionLevel(0); // دقيق
        req.setUsesLanguageCorrection(false);
        if (langs) req.setRecognitionLanguages($(langs));
        else { try { req.setAutomaticallyDetectsLanguage(true); } catch (e) {} }
        var h = $.VNImageRequestHandler.alloc.initWithURLOptions(url, $({}));
        var ok = h.performRequestsError($([req]), null);
        if (!ok) return null;
        return req.results;
    }
    var res = null;
    try { res = ocr(['ar', 'en']); } catch (e) { res = null; }
    if (!res || res.count == 0) { try { res = ocr(null); } catch (e) { res = null; } }
    if (!res) return '';
    var out = [];
    for (var i = 0; i < res.count; i++) {
        var o = res.objectAtIndex(i);
        var c = o.topCandidates(1);
        if (c.count == 0) continue;
        var s = c.objectAtIndex(0).string;
        var b = o.boundingBox;
        var b64 = s.dataUsingEncoding($.NSUTF8StringEncoding).base64EncodedStringWithOptions(0).js;
        out.push([b.origin.x, b.origin.y, b.size.width, b.size.height, b64].join('\t'));
    }
    return out.join('\n');
}
