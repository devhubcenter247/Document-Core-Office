# R8 rules of DocSDK. They apply when the SDK itself is built and again in every app that
# uses it and turns on minify: what native code or reflection finds by name must keep its name.

# ---- public API ----
-keep public class com.editor.docsdk.** { public protected *; }
# PDF viewer API: integrators may embed PDFView directly when they need its advanced
# configuration, callbacks, document metadata, table of contents, links or annotations.
# Keep the complete public/protected surface and the names of public types used by it.
-keep public class com.reader.pdfviewer.** { public protected *; }
# the edit UI (panels, bar, overlays, dialogs) and what their public signatures use, for apps
# that build their own edit screen or subclass a panel
-keep public class com.wxiwei.office.editor.ui.** { public protected *; }
-keep public class com.wxiwei.office.reader.** { public protected *; }
-keep public class com.wxiwei.office.editor.EditResult { public protected *; }
-keep public class com.wxiwei.office.editor.EditResult$* { public protected *; }
-keep public class com.wxiwei.office.editor.Reason { public protected *; }
-keep public class com.wxiwei.office.editor.pptx.SlideScript { public protected *; }
-keep public class com.wxiwei.office.editor.pptx.SlideEffect { public protected *; }
-keep public class com.wxiwei.office.editor.pptx.SlideEffect$* { public protected *; }
# playing a slideshow: the transition and links of a SlideScript, the script of a file that is only
# read, and the layers PageThumbnails.slideLayers draws (the slide without its animated shapes,
# and each of those)
-keep public class com.wxiwei.office.editor.pptx.SlideTransition { public protected *; }
-keep public class com.wxiwei.office.editor.pptx.SlideTransition$* { public protected *; }
-keep public class com.wxiwei.office.editor.pptx.SlideLink { public protected *; }
# where a link is on the slide (SlideLink.rectEmu)
-keep public class com.wxiwei.office.editor.pptx.Rect { public protected *; }
-keep public class com.wxiwei.office.editor.pptx.PptxEditor {
    public <init>(java.io.File);
    public java.util.List showScript();
}
-keep public class com.wxiwei.office.pg.view.SlideDrawKit$Layers { public protected *; }
-keep class kotlin.Metadata { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# ---- native code (pdfium JNI bridge) ----
-keep class com.reader.pdfviewer.pdfium.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ---- classes found by name or by reflection ----
# open-source parts (Apache POI, dom4j, bundled third-party libraries) keep their names: their
# record factories make classes from constructors and fields looked up by name
-keep class com.wxiwei.office.fc.hslf.** { *; }
-keep class com.wxiwei.office.fc.hssf.** { *; }
-keep class com.wxiwei.office.fc.hwpf.** { *; }
-keep class com.wxiwei.office.fc.ddf.** { *; }
-keep class com.wxiwei.office.fc.poifs.** { *; }
-keep class com.wxiwei.office.fc.hpsf.** { *; }
-keep class com.wxiwei.office.fc.util.** { *; }
-keep class com.wxiwei.office.fc.ss.** { *; }
-keep class com.wxiwei.office.fc.dom4j.** { *; }
-keep class com.wxiwei.office.thirdpart.** { *; }
# encrypted Office files: the decryption engines are found by their simple names
-keep class com.wxiwei.office.system.EncryptedOoxml { *; }
-keep class com.wxiwei.office.system.EncryptedOoxml$* { *; }
-keep class com.wxiwei.office.res.ResConstant { *; }

# ---- libraries the SDK uses ----
# optional parts of the bundled open-source code that Android does not have
-dontwarn java.awt.**
-dontwarn javax.xml.bind.**
-dontwarn org.xmlpull.v1.**
-dontwarn org.w3c.dom.**
-dontwarn org.gjt.xpp.**
