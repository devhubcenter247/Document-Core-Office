/*
 * Modifications Copyright (c) 2026 dongb2002. All rights reserved.
 *
 * This file is based on third-party open-source code and has been modified by dongb2002.
 * The modifications are proprietary to dongb2002. The original copyright and license notice
 * of this file, where present below, remains in effect for the original portions.
 */
#include "util.hpp"

extern "C" {
    #include <unistd.h>
    #include <sys/mman.h>
    #include <sys/stat.h>
    #include <string.h>
    #include <stdio.h>
}

#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/bitmap.h>
#include <utils/Mutex.h>
using namespace android;

#include <fpdfview.h>
#include <fpdf_doc.h>
#include <fpdf_text.h>
#include <fpdf_annot.h>
#include <fpdf_save.h>
#include <fpdf_edit.h>
#include <fpdf_ppo.h>
#include <fpdf_formfill.h>
#include <fpdf_flatten.h>
#include <map>
#include <initializer_list>
#include <algorithm>
#include <string>
#include <vector>
#include <cmath>

static Mutex sLibraryLock;

static int sLibraryReferenceCount = 0;

static void initLibraryIfNeed(){
    Mutex::Autolock lock(sLibraryLock);
    if(sLibraryReferenceCount == 0){
        LOGD("Init FPDF library");
        FPDF_InitLibrary();
    }
    sLibraryReferenceCount++;
}

static void destroyLibraryIfNeed(){
    Mutex::Autolock lock(sLibraryLock);
    sLibraryReferenceCount--;
    if(sLibraryReferenceCount == 0){
        LOGD("Destroy FPDF library");
        FPDF_DestroyLibrary();
    }
}

struct rgb {
    uint8_t red;
    uint8_t green;
    uint8_t blue;
};

class DocumentFile {
    private:
    int fileFd;

    public:
    FPDF_DOCUMENT pdfDocument = NULL;
    size_t fileSize;
    std::vector<unsigned char> editSnapshotBytes;
    std::map<std::string, FPDF_FONT> editFonts;
    std::map<std::string, std::vector<unsigned char>> editFontBytes;
    // Interactive form of the document, set up by nativeInitForms; NULL without one.
    FPDF_FORMHANDLE form = NULL;
    FPDF_FORMFILLINFO* formInfo = NULL;

    DocumentFile() { initLibraryIfNeed(); }
    ~DocumentFile();
};
// Pages loaded while a document has a form, with that form: rendering draws the fields over them.
static std::map<FPDF_PAGE, FPDF_FORMHANDLE> sPageForms;

DocumentFile::~DocumentFile(){
    if(form != NULL){
        for (auto it = sPageForms.begin(); it != sPageForms.end();) {
            if (it->second == form) it = sPageForms.erase(it); else ++it;
        }
        FPDFDOC_ExitFormFillEnvironment(form);
        form = NULL;
    }
    delete formInfo;
    formInfo = NULL;
    if(pdfDocument != NULL){
        for (auto& entry : editFonts) FPDFFont_Close(entry.second);
        editFonts.clear();
        FPDF_CloseDocument(pdfDocument);
    }

    destroyLibraryIfNeed();
}

template <class string_type>
inline typename string_type::value_type* WriteInto(string_type* str, size_t length_with_null) {
  str->reserve(length_with_null);
  str->resize(length_with_null - 1);
  return &((*str)[0]);
}

inline long getFileSize(int fd){
    struct stat file_state;

    if(fstat(fd, &file_state) >= 0){
        return (long)(file_state.st_size);
    }else{
        LOGE("Error getting file size");
        return 0;
    }
}

static char* getErrorDescription(const long error) {
    char* description = NULL;
    switch(error) {
        case FPDF_ERR_SUCCESS:
            asprintf(&description, "No error.");
            break;
        case FPDF_ERR_FILE:
            asprintf(&description, "File not found or could not be opened.");
            break;
        case FPDF_ERR_FORMAT:
            asprintf(&description, "File not in PDF format or corrupted.");
            break;
        case FPDF_ERR_PASSWORD:
            asprintf(&description, "Incorrect password.");
            break;
        case FPDF_ERR_SECURITY:
            asprintf(&description, "Unsupported security scheme.");
            break;
        case FPDF_ERR_PAGE:
            asprintf(&description, "Page not found or content error.");
            break;
        default:
            asprintf(&description, "Unknown error.");
    }

    return description;
}

int jniThrowException(JNIEnv* env, const char* className, const char* message) {
    jclass exClass = env->FindClass(className);
    if (exClass == NULL) {
        LOGE("Unable to find exception class %s", className);
        return -1;
    }

    if(env->ThrowNew(exClass, message ) != JNI_OK) {
        LOGE("Failed throwing '%s' '%s'", className, message);
        return -1;
    }

    return 0;
}

int jniThrowExceptionFmt(JNIEnv* env, const char* className, const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    char msgBuf[512];
    vsnprintf(msgBuf, sizeof(msgBuf), fmt, args);
    return jniThrowException(env, className, msgBuf);
    va_end(args);
}

jobject NewLong(JNIEnv* env, jlong value) {
    jclass cls = env->FindClass("java/lang/Long");
    jmethodID methodID = env->GetMethodID(cls, "<init>", "(J)V");
    return env->NewObject(cls, methodID, value);
}

jobject NewInteger(JNIEnv* env, jint value) {
    jclass cls = env->FindClass("java/lang/Integer");
    jmethodID methodID = env->GetMethodID(cls, "<init>", "(I)V");
    return env->NewObject(cls, methodID, value);
}

uint16_t rgbTo565(rgb *color) {
    return ((color->red >> 3) << 11) | ((color->green >> 2) << 5) | (color->blue >> 3);
}

void rgbBitmapTo565(void *source, int sourceStride, void *dest, AndroidBitmapInfo *info) {
    rgb *srcLine;
    uint16_t *dstLine;
    int y, x;
    for (y = 0; y < info->height; y++) {
        srcLine = (rgb*) source;
        dstLine = (uint16_t*) dest;
        for (x = 0; x < info->width; x++) {
            dstLine[x] = rgbTo565(&srcLine[x]);
        }
        source = (char*) source + sourceStride;
        dest = (char*) dest + info->stride;
    }
}

extern "C" { //For JNI support

static int getBlock(void* param, unsigned long position, unsigned char* outBuffer,
        unsigned long size) {
    const int fd = reinterpret_cast<intptr_t>(param);
    const int readCount = pread(fd, outBuffer, size, position);
    if (readCount < 0) {
        LOGE("Cannot read from file descriptor. Error:%d", errno);
        return 0;
    }
    return 1;
}

JNI_FUNC(jlong, PdfiumCore, nativeOpenDocument)(JNI_ARGS, jint fd, jstring password){

    size_t fileLength = (size_t)getFileSize(fd);
    if(fileLength <= 0) {
        jniThrowException(env, "java/io/IOException",
                                    "File is empty");
        return -1;
    }

    DocumentFile *docFile = new DocumentFile();

    FPDF_FILEACCESS loader;
    loader.m_FileLen = fileLength;
    loader.m_Param = reinterpret_cast<void*>(intptr_t(fd));
    loader.m_GetBlock = &getBlock;

    const char *cpassword = NULL;
    if(password != NULL) {
        cpassword = env->GetStringUTFChars(password, NULL);
    }

    FPDF_DOCUMENT document = FPDF_LoadCustomDocument(&loader, cpassword);

    if(cpassword != NULL) {
        env->ReleaseStringUTFChars(password, cpassword);
    }

    if (!document) {
        delete docFile;

        const long errorNum = FPDF_GetLastError();
        if(errorNum == FPDF_ERR_PASSWORD) {
            jniThrowException(env, "com/reader/pdfviewer/pdfium/PdfPasswordException",
                                    "Password required or incorrect password.");
        } else {
            char* error = getErrorDescription(errorNum);
            jniThrowExceptionFmt(env, "java/io/IOException",
                                    "cannot create document: %s", error);

            free(error);
        }

        return -1;
    }

    docFile->pdfDocument = document;

    return reinterpret_cast<jlong>(docFile);
}

JNI_FUNC(jlong, PdfiumCore, nativeOpenMemDocument)(JNI_ARGS, jbyteArray data, jstring password){
    DocumentFile *docFile = new DocumentFile();

    const char *cpassword = NULL;
    if(password != NULL) {
        cpassword = env->GetStringUTFChars(password, NULL);
    }

    jbyte *cData = env->GetByteArrayElements(data, NULL);
    int size = (int) env->GetArrayLength(data);
    jbyte *cDataCopy = new jbyte[size];
    memcpy(cDataCopy, cData, size);
    FPDF_DOCUMENT document = FPDF_LoadMemDocument( reinterpret_cast<const void*>(cDataCopy),
                                                          size, cpassword);
    env->ReleaseByteArrayElements(data, cData, JNI_ABORT);

    if(cpassword != NULL) {
        env->ReleaseStringUTFChars(password, cpassword);
    }

    if (!document) {
        delete docFile;

        const long errorNum = FPDF_GetLastError();
        if(errorNum == FPDF_ERR_PASSWORD) {
            jniThrowException(env, "com/reader/pdfviewer/pdfium/PdfPasswordException",
                                    "Password required or incorrect password.");
        } else {
            char* error = getErrorDescription(errorNum);
            jniThrowExceptionFmt(env, "java/io/IOException",
                                    "cannot create document: %s", error);

            free(error);
        }

        return -1;
    }

    docFile->pdfDocument = document;

    return reinterpret_cast<jlong>(docFile);
}

JNI_FUNC(jint, PdfiumCore, nativeGetPageCount)(JNI_ARGS, jlong documentPtr){
    DocumentFile *doc = reinterpret_cast<DocumentFile*>(documentPtr);
    return (jint)FPDF_GetPageCount(doc->pdfDocument);
}

JNI_FUNC(void, PdfiumCore, nativeCloseDocument)(JNI_ARGS, jlong documentPtr){
    DocumentFile *doc = reinterpret_cast<DocumentFile*>(documentPtr);
    delete doc;
}

static jlong loadPageInternal(JNIEnv *env, DocumentFile *doc, int pageIndex){
    try{
        if(doc == NULL) throw "Get page document null";

        FPDF_DOCUMENT pdfDoc = doc->pdfDocument;
        if(pdfDoc != NULL){
            FPDF_PAGE page = FPDF_LoadPage(pdfDoc, pageIndex);
            if (page == NULL) {
                throw "Loaded page is null";
            }
            if (doc->form != NULL) {
                FORM_OnAfterLoadPage(page, doc->form);
                sPageForms[page] = doc->form;
            }
            return reinterpret_cast<jlong>(page);
        }else{
            throw "Get page pdf document null";
        }

    }catch(const char *msg){
        LOGE("%s", msg);

        jniThrowException(env, "java/lang/IllegalStateException",
                                "cannot load page");

        return -1;
    }
}

static void closePageInternal(jlong pagePtr) {
    FPDF_PAGE page = reinterpret_cast<FPDF_PAGE>(pagePtr);
    auto found = sPageForms.find(page);
    if (found != sPageForms.end()) {
        FORM_OnBeforeClosePage(page, found->second);
        sPageForms.erase(found);
    }
    FPDF_ClosePage(page);
}

/** Draws the form fields of [page] (their current values) over what FPDF_RenderPageBitmap drew. */
static void drawFormFields(FPDF_BITMAP bitmap, FPDF_PAGE page, int startX, int startY, int sizeX, int sizeY, int flags) {
    auto found = sPageForms.find(page);
    if (found != sPageForms.end()) FPDF_FFLDraw(found->second, bitmap, page, startX, startY, sizeX, sizeY, 0, flags);
}

JNI_FUNC(jlong, PdfiumCore, nativeLoadPage)(JNI_ARGS, jlong docPtr, jint pageIndex){
    DocumentFile *doc = reinterpret_cast<DocumentFile*>(docPtr);
    return loadPageInternal(env, doc, (int)pageIndex);
}
JNI_FUNC(jlongArray, PdfiumCore, nativeLoadPages)(JNI_ARGS, jlong docPtr, jint fromIndex, jint toIndex){
    DocumentFile *doc = reinterpret_cast<DocumentFile*>(docPtr);

    if(toIndex < fromIndex) return NULL;
    jlong pages[ toIndex - fromIndex + 1 ];

    int i;
    for(i = 0; i <= (toIndex - fromIndex); i++){
        pages[i] = loadPageInternal(env, doc, (int)(i + fromIndex));
    }

    jlongArray javaPages = env -> NewLongArray( (jsize)(toIndex - fromIndex + 1) );
    env -> SetLongArrayRegion(javaPages, 0, (jsize)(toIndex - fromIndex + 1), (const jlong*)pages);

    return javaPages;
}

JNI_FUNC(void, PdfiumCore, nativeClosePage)(JNI_ARGS, jlong pagePtr){ closePageInternal(pagePtr); }
JNI_FUNC(void, PdfiumCore, nativeClosePages)(JNI_ARGS, jlongArray pagesPtr){
    int length = (int)(env -> GetArrayLength(pagesPtr));
    jlong *pages = env -> GetLongArrayElements(pagesPtr, NULL);

    int i;
    for(i = 0; i < length; i++){ closePageInternal(pages[i]); }
}

JNI_FUNC(jint, PdfiumCore, nativeGetPageWidthPixel)(JNI_ARGS, jlong pagePtr, jint dpi){
    FPDF_PAGE page = reinterpret_cast<FPDF_PAGE>(pagePtr);
    return (jint)(FPDF_GetPageWidth(page) * dpi / 72);
}
JNI_FUNC(jint, PdfiumCore, nativeGetPageHeightPixel)(JNI_ARGS, jlong pagePtr, jint dpi){
    FPDF_PAGE page = reinterpret_cast<FPDF_PAGE>(pagePtr);
    return (jint)(FPDF_GetPageHeight(page) * dpi / 72);
}

JNI_FUNC(jint, PdfiumCore, nativeGetPageWidthPoint)(JNI_ARGS, jlong pagePtr){
    FPDF_PAGE page = reinterpret_cast<FPDF_PAGE>(pagePtr);
    return (jint)FPDF_GetPageWidth(page);
}
JNI_FUNC(jint, PdfiumCore, nativeGetPageHeightPoint)(JNI_ARGS, jlong pagePtr){
    FPDF_PAGE page = reinterpret_cast<FPDF_PAGE>(pagePtr);
    return (jint)FPDF_GetPageHeight(page);
}
JNI_FUNC(jobject, PdfiumCore, nativeGetPageSizeByIndex)(JNI_ARGS, jlong docPtr, jint pageIndex, jint dpi){
    DocumentFile *doc = reinterpret_cast<DocumentFile*>(docPtr);
    if(doc == NULL) {
        LOGE("Document is null");

        jniThrowException(env, "java/lang/IllegalStateException",
                               "Document is null");
        return NULL;
    }

    double width, height;
    int result = FPDF_GetPageSizeByIndex(doc->pdfDocument, pageIndex, &width, &height);

    if (result == 0) {
        width = 0;
        height = 0;
    }

    jint widthInt = (jint) (width * dpi / 72);
    jint heightInt = (jint) (height * dpi / 72);

    jclass clazz = env->FindClass("com/reader/pdfviewer/pdfium/util/Size");
    jmethodID constructorID = env->GetMethodID(clazz, "<init>", "(II)V");
    return env->NewObject(clazz, constructorID, widthInt, heightInt);
}

static void renderPageInternal( FPDF_PAGE page,
                                ANativeWindow_Buffer *windowBuffer,
                                int startX, int startY,
                                int canvasHorSize, int canvasVerSize,
                                int drawSizeHor, int drawSizeVer,
                                bool renderAnnot){

    FPDF_BITMAP pdfBitmap = FPDFBitmap_CreateEx( canvasHorSize, canvasVerSize,
                                                 FPDFBitmap_BGRA,
                                                 windowBuffer->bits, (int)(windowBuffer->stride) * 4);

    /*LOGD("Start X: %d", startX);
    LOGD("Start Y: %d", startY);
    LOGD("Canvas Hor: %d", canvasHorSize);
    LOGD("Canvas Ver: %d", canvasVerSize);
    LOGD("Draw Hor: %d", drawSizeHor);
    LOGD("Draw Ver: %d", drawSizeVer);*/

    if(drawSizeHor < canvasHorSize || drawSizeVer < canvasVerSize){
        FPDFBitmap_FillRect( pdfBitmap, 0, 0, canvasHorSize, canvasVerSize,
                             0x848484FF); //Gray
    }

    int baseHorSize = (canvasHorSize < drawSizeHor)? canvasHorSize : drawSizeHor;
    int baseVerSize = (canvasVerSize < drawSizeVer)? canvasVerSize : drawSizeVer;
    int baseX = (startX < 0)? 0 : startX;
    int baseY = (startY < 0)? 0 : startY;
    int flags = FPDF_REVERSE_BYTE_ORDER;

    if(renderAnnot) {
    	flags |= FPDF_ANNOT;
    }

    FPDFBitmap_FillRect( pdfBitmap, baseX, baseY, baseHorSize, baseVerSize,
                         0xFFFFFFFF); //White

    FPDF_RenderPageBitmap( pdfBitmap, page,
                           startX, startY,
                           drawSizeHor, drawSizeVer,
                           0, flags );
    if(renderAnnot) drawFormFields(pdfBitmap, page, startX, startY, drawSizeHor, drawSizeVer, flags);
    FPDFBitmap_Destroy(pdfBitmap);
}

JNI_FUNC(void, PdfiumCore, nativeRenderPage)(JNI_ARGS, jlong pagePtr, jobject objSurface,
                                             jint dpi, jint startX, jint startY,
                                             jint drawSizeHor, jint drawSizeVer,
                                             jboolean renderAnnot){
    ANativeWindow *nativeWindow = ANativeWindow_fromSurface(env, objSurface);
    if(nativeWindow == NULL){
        LOGE("native window pointer null");
        return;
    }
    FPDF_PAGE page = reinterpret_cast<FPDF_PAGE>(pagePtr);

    if(page == NULL || nativeWindow == NULL){
        LOGE("Render page pointers invalid");
        return;
    }

    if(ANativeWindow_getFormat(nativeWindow) != WINDOW_FORMAT_RGBA_8888){
        LOGD("Set format to RGBA_8888");
        ANativeWindow_setBuffersGeometry( nativeWindow,
                                          ANativeWindow_getWidth(nativeWindow),
                                          ANativeWindow_getHeight(nativeWindow),
                                          WINDOW_FORMAT_RGBA_8888 );
    }

    ANativeWindow_Buffer buffer;
    int ret;
    if( (ret = ANativeWindow_lock(nativeWindow, &buffer, NULL)) != 0 ){
        LOGE("Locking native window failed: %s", strerror(ret * -1));
        return;
    }

    renderPageInternal(page, &buffer,
                       (int)startX, (int)startY,
                       buffer.width, buffer.height,
                       (int)drawSizeHor, (int)drawSizeVer,
                       (bool)renderAnnot);

    ANativeWindow_unlockAndPost(nativeWindow);
    ANativeWindow_release(nativeWindow);
}

JNI_FUNC(void, PdfiumCore, nativeRenderPageBitmap)(JNI_ARGS, jlong pagePtr, jobject bitmap,
                                             jint dpi, jint startX, jint startY,
                                             jint drawSizeHor, jint drawSizeVer,
                                             jboolean renderAnnot){

    FPDF_PAGE page = reinterpret_cast<FPDF_PAGE>(pagePtr);

    if(page == NULL || bitmap == NULL){
        LOGE("Render page pointers invalid");
        return;
    }

    AndroidBitmapInfo info;
    int ret;
    if((ret = AndroidBitmap_getInfo(env, bitmap, &info)) < 0) {
        LOGE("Fetching bitmap info failed: %s", strerror(ret * -1));
        return;
    }

    int canvasHorSize = info.width;
    int canvasVerSize = info.height;

    if(info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 && info.format != ANDROID_BITMAP_FORMAT_RGB_565){
        LOGE("Bitmap format must be RGBA_8888 or RGB_565");
        return;
    }

    void *addr;
    if( (ret = AndroidBitmap_lockPixels(env, bitmap, &addr)) != 0 ){
        LOGE("Locking bitmap failed: %s", strerror(ret * -1));
        return;
    }

    void *tmp;
    int format;
    int sourceStride;
    if (info.format == ANDROID_BITMAP_FORMAT_RGB_565) {
        tmp = malloc(canvasVerSize * canvasHorSize * sizeof(rgb));
        sourceStride = canvasHorSize * sizeof(rgb);
        format = FPDFBitmap_BGR;
    } else {
        tmp = addr;
        sourceStride = info.stride;
        format = FPDFBitmap_BGRA;
    }

    FPDF_BITMAP pdfBitmap = FPDFBitmap_CreateEx( canvasHorSize, canvasVerSize,
                                                     format, tmp, sourceStride);

    /*LOGD("Start X: %d", startX);
    LOGD("Start Y: %d", startY);
    LOGD("Canvas Hor: %d", canvasHorSize);
    LOGD("Canvas Ver: %d", canvasVerSize);
    LOGD("Draw Hor: %d", drawSizeHor);
    LOGD("Draw Ver: %d", drawSizeVer);*/

    if(drawSizeHor < canvasHorSize || drawSizeVer < canvasVerSize){
        FPDFBitmap_FillRect( pdfBitmap, 0, 0, canvasHorSize, canvasVerSize,
                             0x848484FF); //Gray
    }

    int baseHorSize = (canvasHorSize < drawSizeHor)? canvasHorSize : (int)drawSizeHor;
    int baseVerSize = (canvasVerSize < drawSizeVer)? canvasVerSize : (int)drawSizeVer;
    int baseX = (startX < 0)? 0 : (int)startX;
    int baseY = (startY < 0)? 0 : (int)startY;
    int flags = FPDF_REVERSE_BYTE_ORDER;

    if(renderAnnot) {
    	flags |= FPDF_ANNOT;
    }

    FPDFBitmap_FillRect( pdfBitmap, baseX, baseY, baseHorSize, baseVerSize,
                         0xFFFFFFFF); //White

    FPDF_RenderPageBitmap( pdfBitmap, page,
                           startX, startY,
                           (int)drawSizeHor, (int)drawSizeVer,
                           0, flags );
    if(renderAnnot) drawFormFields(pdfBitmap, page, (int)startX, (int)startY, (int)drawSizeHor, (int)drawSizeVer, flags);
    FPDFBitmap_Destroy(pdfBitmap);

    if (info.format == ANDROID_BITMAP_FORMAT_RGB_565) {
        rgbBitmapTo565(tmp, sourceStride, addr, &info);
        free(tmp);
    }

    AndroidBitmap_unlockPixels(env, bitmap);
}

JNI_FUNC(jstring, PdfiumCore, nativeGetDocumentMetaText)(JNI_ARGS, jlong docPtr, jstring tag) {
    const char *ctag = env->GetStringUTFChars(tag, NULL);
    if (ctag == NULL) {
        return env->NewStringUTF("");
    }
    DocumentFile *doc = reinterpret_cast<DocumentFile*>(docPtr);

    size_t bufferLen = FPDF_GetMetaText(doc->pdfDocument, ctag, NULL, 0);
    if (bufferLen <= 2) {
        return env->NewStringUTF("");
    }
    std::wstring text;
    FPDF_GetMetaText(doc->pdfDocument, ctag, WriteInto(&text, bufferLen + 1), bufferLen);
    env->ReleaseStringUTFChars(tag, ctag);
    return env->NewString((jchar*) text.c_str(), bufferLen / 2 - 1);
}

JNI_FUNC(jobject, PdfiumCore, nativeGetFirstChildBookmark)(JNI_ARGS, jlong docPtr, jobject bookmarkPtr) {
    DocumentFile *doc = reinterpret_cast<DocumentFile*>(docPtr);
    FPDF_BOOKMARK parent;
    if(bookmarkPtr == NULL) {
        parent = NULL;
    } else {
        jclass longClass = env->GetObjectClass(bookmarkPtr);
        jmethodID longValueMethod = env->GetMethodID(longClass, "longValue", "()J");

        jlong ptr = env->CallLongMethod(bookmarkPtr, longValueMethod);
        parent = reinterpret_cast<FPDF_BOOKMARK>(ptr);
    }
    FPDF_BOOKMARK bookmark = FPDFBookmark_GetFirstChild(doc->pdfDocument, parent);
    if (bookmark == NULL) {
        return NULL;
    }
    return NewLong(env, reinterpret_cast<jlong>(bookmark));
}

JNI_FUNC(jobject, PdfiumCore, nativeGetSiblingBookmark)(JNI_ARGS, jlong docPtr, jlong bookmarkPtr) {
    DocumentFile *doc = reinterpret_cast<DocumentFile*>(docPtr);
    FPDF_BOOKMARK parent = reinterpret_cast<FPDF_BOOKMARK>(bookmarkPtr);
    FPDF_BOOKMARK bookmark = FPDFBookmark_GetNextSibling(doc->pdfDocument, parent);
    if (bookmark == NULL) {
        return NULL;
    }
    return NewLong(env, reinterpret_cast<jlong>(bookmark));
}

JNI_FUNC(jstring, PdfiumCore, nativeGetBookmarkTitle)(JNI_ARGS, jlong bookmarkPtr) {
    FPDF_BOOKMARK bookmark = reinterpret_cast<FPDF_BOOKMARK>(bookmarkPtr);
    size_t bufferLen = FPDFBookmark_GetTitle(bookmark, NULL, 0);
    if (bufferLen <= 2) {
        return env->NewStringUTF("");
    }
    std::wstring title;
    FPDFBookmark_GetTitle(bookmark, WriteInto(&title, bufferLen + 1), bufferLen);
    return env->NewString((jchar*) title.c_str(), bufferLen / 2 - 1);
}

JNI_FUNC(jlong, PdfiumCore, nativeGetBookmarkDestIndex)(JNI_ARGS, jlong docPtr, jlong bookmarkPtr) {
    DocumentFile *doc = reinterpret_cast<DocumentFile*>(docPtr);
    FPDF_BOOKMARK bookmark = reinterpret_cast<FPDF_BOOKMARK>(bookmarkPtr);

    FPDF_DEST dest = FPDFBookmark_GetDest(doc->pdfDocument, bookmark);
    if (dest == NULL) {
        return -1;
    }
    return (jlong) FPDFDest_GetDestPageIndex(doc->pdfDocument, dest);
}

JNI_FUNC(jlongArray, PdfiumCore, nativeGetPageLinks)(JNI_ARGS, jlong pagePtr) {
    FPDF_PAGE page = reinterpret_cast<FPDF_PAGE>(pagePtr);
    int pos = 0;
    std::vector<jlong> links;
    FPDF_LINK link;
    while (FPDFLink_Enumerate(page, &pos, &link)) {
        links.push_back(reinterpret_cast<jlong>(link));
    }

    jlongArray result = env->NewLongArray(links.size());
    env->SetLongArrayRegion(result, 0, links.size(), &links[0]);
    return result;
}

JNI_FUNC(jobject, PdfiumCore, nativeGetDestPageIndex)(JNI_ARGS, jlong docPtr, jlong linkPtr) {
    DocumentFile *doc = reinterpret_cast<DocumentFile*>(docPtr);
    FPDF_LINK link = reinterpret_cast<FPDF_LINK>(linkPtr);
    FPDF_DEST dest = FPDFLink_GetDest(doc->pdfDocument, link);
    if (dest == NULL) {
        return NULL;
    }
    int index = FPDFDest_GetDestPageIndex(doc->pdfDocument, dest);
    if (index < 0) {
        return NULL;
    }
    return NewInteger(env, (jint) index);
}

JNI_FUNC(jstring, PdfiumCore, nativeGetLinkURI)(JNI_ARGS, jlong docPtr, jlong linkPtr){
    DocumentFile *doc = reinterpret_cast<DocumentFile*>(docPtr);
    FPDF_LINK link = reinterpret_cast<FPDF_LINK>(linkPtr);
    FPDF_ACTION action = FPDFLink_GetAction(link);
    if (action == NULL) {
        return NULL;
    }
    size_t bufferLen = FPDFAction_GetURIPath(doc->pdfDocument, action, NULL, 0);
    if (bufferLen <= 0) {
        return env->NewStringUTF("");
    }
    std::string uri;
    FPDFAction_GetURIPath(doc->pdfDocument, action, WriteInto(&uri, bufferLen), bufferLen);
    return env->NewStringUTF(uri.c_str());
}

JNI_FUNC(jobject, PdfiumCore, nativeGetLinkRect)(JNI_ARGS, jlong linkPtr) {
    FPDF_LINK link = reinterpret_cast<FPDF_LINK>(linkPtr);
    FS_RECTF fsRectF;
    FPDF_BOOL result = FPDFLink_GetAnnotRect(link, &fsRectF);

    if (!result) {
        return NULL;
    }

    jclass clazz = env->FindClass("android/graphics/RectF");
    jmethodID constructorID = env->GetMethodID(clazz, "<init>", "(FFFF)V");
    return env->NewObject(clazz, constructorID, fsRectF.left, fsRectF.top, fsRectF.right, fsRectF.bottom);
}

JNI_FUNC(jobject, PdfiumCore, nativePageCoordsToDevice)(JNI_ARGS, jlong pagePtr, jint startX, jint startY, jint sizeX,
                                            jint sizeY, jint rotate, jdouble pageX, jdouble pageY) {
    FPDF_PAGE page = reinterpret_cast<FPDF_PAGE>(pagePtr);
    int deviceX, deviceY;

    FPDF_PageToDevice(page, startX, startY, sizeX, sizeY, rotate, pageX, pageY, &deviceX, &deviceY);

    jclass clazz = env->FindClass("android/graphics/Point");
    jmethodID constructorID = env->GetMethodID(clazz, "<init>", "(II)V");
    return env->NewObject(clazz, constructorID, deviceX, deviceY);
}

JNI_FUNC(jlong, PdfiumCore, nativeLoadTextPage)(JNI_ARGS, jlong pagePtr) {
    FPDF_PAGE page = reinterpret_cast<FPDF_PAGE>(pagePtr);
    return reinterpret_cast<jlong>(FPDFText_LoadPage(page));
}

JNI_FUNC(void, PdfiumCore, nativeCloseTextPage)(JNI_ARGS, jlong textPagePtr) {
    FPDFText_ClosePage(reinterpret_cast<FPDF_TEXTPAGE>(textPagePtr));
}

JNI_FUNC(jint, PdfiumCore, nativeTextCountChars)(JNI_ARGS, jlong textPagePtr) {
    return (jint) FPDFText_CountChars(reinterpret_cast<FPDF_TEXTPAGE>(textPagePtr));
}

JNI_FUNC(jstring, PdfiumCore, nativeTextGetText)(JNI_ARGS, jlong textPagePtr, jint startIndex, jint count) {
    if (count <= 0) {
        return env->NewStringUTF("");
    }
    std::vector<unsigned short> buffer(count + 1, 0);
    int written = FPDFText_GetText(reinterpret_cast<FPDF_TEXTPAGE>(textPagePtr), startIndex, count, buffer.data());
    // written includes the trailing terminator
    int length = written > 0 ? written - 1 : 0;
    return env->NewString(reinterpret_cast<const jchar*>(buffer.data()), length);
}

JNI_FUNC(jint, PdfiumCore, nativeTextGetCharIndexAtPos)(JNI_ARGS, jlong textPagePtr, jdouble x, jdouble y,
                                            jdouble toleranceX, jdouble toleranceY) {
    return (jint) FPDFText_GetCharIndexAtPos(reinterpret_cast<FPDF_TEXTPAGE>(textPagePtr), x, y, toleranceX, toleranceY);
}

JNI_FUNC(jobject, PdfiumCore, nativeTextGetCharBox)(JNI_ARGS, jlong textPagePtr, jint index) {
    double left, right, bottom, top;
    FPDFText_GetCharBox(reinterpret_cast<FPDF_TEXTPAGE>(textPagePtr), index, &left, &right, &bottom, &top);

    jclass clazz = env->FindClass("android/graphics/RectF");
    jmethodID constructorID = env->GetMethodID(clazz, "<init>", "(FFFF)V");
    return env->NewObject(clazz, constructorID, (jfloat) left, (jfloat) top, (jfloat) right, (jfloat) bottom);
}

/**
 * FPDFText_GetCharBox of every character in one call: left, top, right, bottom per character,
 * the same values nativeTextGetCharBox returns one by one. A character without a box gets 0s.
 */
JNI_FUNC(jfloatArray, PdfiumCore, nativeTextGetCharBoxes)(JNI_ARGS, jlong textPagePtr) {
    FPDF_TEXTPAGE textPage = reinterpret_cast<FPDF_TEXTPAGE>(textPagePtr);
    int count = std::max(0, FPDFText_CountChars(textPage));
    std::vector<jfloat> boxes(count * 4, 0.0f);
    for (int i = 0; i < count; i++) {
        double left, right, bottom, top;
        if (FPDFText_GetCharBox(textPage, i, &left, &right, &bottom, &top)) {
            boxes[i * 4] = (jfloat) left;
            boxes[i * 4 + 1] = (jfloat) top;
            boxes[i * 4 + 2] = (jfloat) right;
            boxes[i * 4 + 3] = (jfloat) bottom;
        }
    }
    jfloatArray result = env->NewFloatArray(count * 4);
    if (result != NULL && count > 0) env->SetFloatArrayRegion(result, 0, count * 4, boxes.data());
    return result;
}

/**
 * FPDF_PageToDevice for many points in one call; coords holds x, y pairs in page space and the
 * result the device x, y of each, the same values nativePageCoordsToDevice returns one by one.
 */
JNI_FUNC(jintArray, PdfiumCore, nativePageCoordsToDeviceBatch)(JNI_ARGS, jlong pagePtr, jint startX, jint startY,
                                                               jint sizeX, jint sizeY, jint rotate, jfloatArray coords) {
    FPDF_PAGE page = reinterpret_cast<FPDF_PAGE>(pagePtr);
    jsize length = coords == NULL ? 0 : env->GetArrayLength(coords);
    if (page == NULL || length % 2 != 0) return NULL;
    std::vector<jfloat> points(length);
    if (length > 0) env->GetFloatArrayRegion(coords, 0, length, points.data());
    std::vector<jint> device(length);
    for (jsize i = 0; i < length; i += 2) {
        int x, y;
        FPDF_PageToDevice(page, startX, startY, sizeX, sizeY, rotate, (double) points[i], (double) points[i + 1], &x, &y);
        device[i] = x;
        device[i + 1] = y;
    }
    jintArray result = env->NewIntArray(length);
    if (result != NULL && length > 0) env->SetIntArrayRegion(result, 0, length, device.data());
    return result;
}

/**
 * Add a text markup annotation (underline, strikeout...) covering the given quads.
 * quads holds 8 floats per quad in page coordinates: x1,y1 (top left), x2,y2 (top right),
 * x3,y3 (bottom left), x4,y4 (bottom right).
 */
static std::vector<unsigned short> annotName(JNIEnv* env, jstring name);

JNI_FUNC(jboolean, PdfiumCore, nativeAddTextMarkupAnnot)(JNI_ARGS, jlong pagePtr, jint subtype, jfloatArray quads,
                                                jint r, jint g, jint b, jint a, jstring name) {
    FPDF_PAGE page = reinterpret_cast<FPDF_PAGE>(pagePtr);
    jsize count = quads == NULL ? 0 : env->GetArrayLength(quads);
    if (page == NULL || count < 8 || count % 8 != 0) {
        return JNI_FALSE;
    }
    std::vector<jfloat> values(count);
    env->GetFloatArrayRegion(quads, 0, count, values.data());

    FPDF_ANNOTATION annot = FPDFPage_CreateAnnot(page, subtype);
    if (annot == NULL) {
        return JNI_FALSE;
    }
    auto nm = annotName(env, name);
    bool ok = FPDFAnnot_SetStringValue(annot, "NM", nm.data()) && FPDFAnnot_SetColor(annot, FPDFANNOT_COLORTYPE_Color, r, g, b, a);
    FS_RECTF rect = {values[0], values[1], values[0], values[1]};
    for (jsize i = 0; ok && i < count; i += 8) {
        FS_QUADPOINTSF quad = {values[i], values[i + 1], values[i + 2], values[i + 3],
                               values[i + 4], values[i + 5], values[i + 6], values[i + 7]};
        ok = FPDFAnnot_AppendAttachmentPoints(annot, &quad);
        for (jsize j = 0; j < 8; j += 2) {
            float x = values[i + j];
            float y = values[i + j + 1];
            if (x < rect.left) rect.left = x;
            if (x > rect.right) rect.right = x;
            if (y < rect.bottom) rect.bottom = y;
            if (y > rect.top) rect.top = y;
        }
    }
    ok = ok && FPDFAnnot_SetRect(annot, &rect);
    ok = ok && FPDFAnnot_SetFlags(annot, FPDF_ANNOT_FLAG_PRINT);
    if (!ok) {
        // Do not leave a half configured annotation in the page
        int index = FPDFPage_GetAnnotIndex(page, annot);
        FPDFPage_CloseAnnot(annot);
        if (index >= 0) {
            FPDFPage_RemoveAnnot(page, index);
        }
        return JNI_FALSE;
    }
    FPDFPage_CloseAnnot(annot);
    return JNI_TRUE;
}

// PDFium strings are null terminated UTF-16LE, including non-ASCII annotation names.
static std::vector<unsigned short> annotName(JNIEnv* env, jstring name) {
    jsize length = env->GetStringLength(name);
    std::vector<unsigned short> value(length + 1, 0);
    env->GetStringRegion(name, 0, length, reinterpret_cast<jchar*>(value.data()));
    return value;
}

JNI_FUNC(jboolean, PdfiumCore, nativeAddInkAnnot)(JNI_ARGS, jlong pagePtr, jfloatArray points,
                                               jfloat width, jint r, jint g, jint b, jint a, jstring name) {
    FPDF_PAGE page = reinterpret_cast<FPDF_PAGE>(pagePtr);
    jsize count = points == NULL ? 0 : env->GetArrayLength(points);
    if (!page || !name || count < 4 || count % 2 || !std::isfinite(width) || width <= 0) return JNI_FALSE;
    std::vector<jfloat> values(count);
    env->GetFloatArrayRegion(points, 0, count, values.data());
    std::vector<FS_POINTF> stroke(count / 2);
    FS_RECTF rect = {values[0], values[1], values[0], values[1]};
    for (jsize i = 0; i < count; i += 2) {
        float x = values[i], y = values[i + 1];
        if (!std::isfinite(x) || !std::isfinite(y)) return JNI_FALSE;
        stroke[i / 2] = {x, y};
        if (x < rect.left) rect.left = x;
        if (x > rect.right) rect.right = x;
        if (y < rect.bottom) rect.bottom = y;
        if (y > rect.top) rect.top = y;
    }
    float padding = width / 2 + 1;
    rect.left -= padding;
    rect.right += padding;
    rect.bottom -= padding;
    rect.top += padding;
    auto value = annotName(env, name);
    FPDF_ANNOTATION annot = FPDFPage_CreateAnnot(page, FPDF_ANNOT_INK);
    if (!annot) return JNI_FALSE;
    bool ok = FPDFAnnot_AddInkStroke(annot, stroke.data(), stroke.size()) >= 0;
    ok = ok && FPDFAnnot_SetColor(annot, FPDFANNOT_COLORTYPE_Color, r, g, b, a);
    ok = ok && FPDFAnnot_SetBorder(annot, 0, 0, width);
    ok = ok && FPDFAnnot_SetRect(annot, &rect);
    ok = ok && FPDFAnnot_SetFlags(annot, FPDF_ANNOT_FLAG_PRINT);
    ok = ok && FPDFAnnot_SetStringValue(annot, "NM", value.data());
    // InkList alone relies on each viewer synthesizing an appearance. Persist a
    // normal appearance as well, keeping the InkList for editing/undo. Do this
    // after SetBorder, which removes an existing appearance stream.
    FPDF_PAGEOBJECT path = ok ? FPDFPageObj_CreateNewPath(values[0], values[1]) : nullptr;
    ok = ok && path;
    for (jsize i = 2; ok && i < count; i += 2) {
        ok = FPDFPath_LineTo(path, values[i], values[i + 1]);
    }
    ok = ok && FPDFPageObj_SetStrokeColor(path, r, g, b, a);
    ok = ok && FPDFPageObj_SetStrokeWidth(path, width);
    ok = ok && FPDFPageObj_SetLineCap(path, FPDF_LINECAP_ROUND);
    ok = ok && FPDFPageObj_SetLineJoin(path, FPDF_LINEJOIN_ROUND);
    ok = ok && FPDFPath_SetDrawMode(path, FPDF_FILLMODE_NONE, true);
    ok = ok && FPDFAnnot_AppendObject(annot, path);
    if (!ok && path) FPDFPageObj_Destroy(path); // Ownership transfers only on success.
    int index = ok ? -1 : FPDFPage_GetAnnotIndex(page, annot);
    FPDFPage_CloseAnnot(annot);
    if (!ok && index >= 0) FPDFPage_RemoveAnnot(page, index);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNI_FUNC(jboolean, PdfiumCore, nativeRemoveAnnotByName)(JNI_ARGS, jlong pagePtr, jstring name) {
    FPDF_PAGE page = reinterpret_cast<FPDF_PAGE>(pagePtr);
    if (!page || !name) return JNI_FALSE;
    auto expected = annotName(env, name);
    int count = FPDFPage_GetAnnotCount(page);
    for (int i = 0; i < count; ++i) {
        FPDF_ANNOTATION annot = FPDFPage_GetAnnot(page, i);
        if (!annot) continue;
        unsigned long bytes = FPDFAnnot_GetStringValue(annot, "NM", NULL, 0);
        bool matches = false;
        if (bytes == expected.size() * sizeof(unsigned short)) {
            std::vector<unsigned short> value(expected.size(), 0);
            matches = FPDFAnnot_GetStringValue(annot, "NM", value.data(), bytes) == bytes && value == expected;
        }
        FPDFPage_CloseAnnot(annot);
        if (matches) return FPDFPage_RemoveAnnot(page, i) ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

JNI_FUNC(jobject, PdfiumCore, nativeDeviceToPageCoords)(JNI_ARGS, jlong pagePtr, jint startX, jint startY,
                                                      jint sizeX, jint sizeY, jint rotate, jint deviceX, jint deviceY) {
    double x, y;
    if (!FPDF_DeviceToPage(reinterpret_cast<FPDF_PAGE>(pagePtr), startX, startY, sizeX, sizeY,
                           rotate, deviceX, deviceY, &x, &y)) return NULL;
    jclass clazz = env->FindClass("android/graphics/PointF");
    jmethodID constructor = env->GetMethodID(clazz, "<init>", "(FF)V");
    return env->NewObject(clazz, constructor, static_cast<jfloat>(x), static_cast<jfloat>(y));
}

// Font handles and backing bytes stay alive until the owning document closes.
static FPDF_FONT editFont(DocumentFile* doc, JNIEnv* env, jstring path) {
    std::string key;
    if (path) {
        const char* chars = env->GetStringUTFChars(path, NULL);
        key = chars;
        env->ReleaseStringUTFChars(path, chars);
    }
    auto found = doc->editFonts.find(key);
    if (found != doc->editFonts.end()) return found->second;
    FPDF_FONT font = NULL;
    if (!key.empty()) {
        FILE* file = fopen(key.c_str(), "rb");
        if (file) {
            if (fseek(file, 0, SEEK_END) == 0) {
                long size = ftell(file);
                if (size > 0 && size <= 64 * 1024 * 1024 && fseek(file, 0, SEEK_SET) == 0) {
                    auto& bytes = doc->editFontBytes[key];
                    bytes.resize(size);
                    if (fread(bytes.data(), 1, size, file) == static_cast<size_t>(size))
                        font = FPDFText_LoadFont(doc->pdfDocument, bytes.data(), bytes.size(), FPDF_FONT_TRUETYPE, true);
                }
            }
            fclose(file);
        }
    }
    if (!font) font = FPDFText_LoadStandardFont(doc->pdfDocument, "Helvetica");
    if (font) doc->editFonts[key] = font;
    return font;
}

static bool finishObjectAnnot(JNIEnv* env, FPDF_PAGE page, FPDF_ANNOTATION annot,
                              const FS_RECTF& rect, jstring name, jstring contents,
                              std::vector<FPDF_PAGEOBJECT>& objects) {
    auto nm = annotName(env, name);
    bool ok = FPDFAnnot_SetRect(annot, &rect) && FPDFAnnot_SetFlags(annot, FPDF_ANNOT_FLAG_PRINT)
        && FPDFAnnot_SetStringValue(annot, "NM", nm.data());
    if (contents) {
        auto text = annotName(env, contents);
        ok = ok && FPDFAnnot_SetStringValue(annot, "Contents", text.data());
    }
    for (auto obj : objects) {
        if (ok && FPDFAnnot_AppendObject(annot, obj)) continue;
        ok = false;
        FPDFPageObj_Destroy(obj);
    }
    int index = ok ? -1 : FPDFPage_GetAnnotIndex(page, annot);
    FPDFPage_CloseAnnot(annot);
    if (!ok && index >= 0) FPDFPage_RemoveAnnot(page, index);
    return ok;
}

JNI_FUNC(jfloatArray, PdfiumCore, nativeAddFreeTextAnnot)(JNI_ARGS, jlong docPtr, jlong pagePtr,
        jstring text, jstring fontPath, jfloat fontSize, jfloat x, jfloat y,
        jint r, jint g, jint b, jint a, jstring name) {
    auto doc = reinterpret_cast<DocumentFile*>(docPtr);
    auto page = reinterpret_cast<FPDF_PAGE>(pagePtr);
    if (!doc || !page || !text || !name || !std::isfinite(fontSize) || fontSize <= 0 ||
        !std::isfinite(x) || !std::isfinite(y)) return NULL;
    FPDF_FONT font = editFont(doc, env, fontPath);
    if (!font) return NULL;
    auto chars = annotName(env, text);
    std::vector<FPDF_PAGEOBJECT> objects;
    FS_RECTF bounds = {};
    bool ok = true;
    size_t start = 0;
    int line = 0;
    for (size_t i = 0; i < chars.size(); ++i) {
        if (chars[i] != '\n' && chars[i] != 0) continue;
        if (i > start) {
            std::vector<unsigned short> value(chars.begin() + start, chars.begin() + i);
            if (!value.empty() && value.back() == '\r') value.pop_back();
            value.push_back(0);
            auto obj = FPDFPageObj_CreateTextObj(doc->pdfDocument, font, fontSize);
            if (!obj) { ok = false; break; }
            objects.push_back(obj);
            if (!FPDFText_SetText(obj, value.data()) || !FPDFPageObj_SetFillColor(obj, r, g, b, a)) {
                ok = false; break;
            }
            FPDFPageObj_Transform(obj, 1, 0, 0, 1, x, y - fontSize * (line + 1) * 1.2f + fontSize * .2f);
            FS_RECTF box;
            if (!FPDFPageObj_GetBounds(obj, &box.left, &box.bottom, &box.right, &box.top)) { ok = false; break; }
            if (objects.size() == 1) bounds = box;
            else {
                bounds.left = std::min(bounds.left, box.left); bounds.right = std::max(bounds.right, box.right);
                bounds.top = std::max(bounds.top, box.top); bounds.bottom = std::min(bounds.bottom, box.bottom);
            }
        }
        start = i + 1;
        ++line;
    }
    if (!ok || objects.empty()) {
        for (auto obj : objects) FPDFPageObj_Destroy(obj);
        return NULL;
    }
    bounds.left -= 2; bounds.right += 2; bounds.top += 2; bounds.bottom -= 2;
    // chromium/8066 fpdf_annot.h explicitly supports AppendObject only for INK/STAMP.
    // Use STAMP for the text appearance; FREETEXT cannot accept these objects.
    auto annot = FPDFPage_CreateAnnot(page, FPDF_ANNOT_STAMP);
    if (!annot) { for (auto obj : objects) FPDFPageObj_Destroy(obj); return NULL; }
    if (!finishObjectAnnot(env, page, annot, bounds, name, text, objects)) return NULL;
    jfloat values[] = {bounds.left, bounds.top, bounds.right, bounds.bottom};
    auto result = env->NewFloatArray(4);
    env->SetFloatArrayRegion(result, 0, 4, values);
    return result;
}

JNI_FUNC(jboolean, PdfiumCore, nativeAddImageAnnot)(JNI_ARGS, jlong docPtr, jlong pagePtr,
        jobject bitmap, jfloat left, jfloat top, jfloat right, jfloat bottom, jstring name) {
    auto doc = reinterpret_cast<DocumentFile*>(docPtr);
    auto page = reinterpret_cast<FPDF_PAGE>(pagePtr);
    if (!doc || !page || !bitmap || !name || !std::isfinite(left) || !std::isfinite(top) ||
        !std::isfinite(right) || !std::isfinite(bottom) || right <= left || top <= bottom) return JNI_FALSE;
    AndroidBitmapInfo info;
    void* pixels = NULL;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || !info.width || !info.height) return JNI_FALSE;
    std::vector<unsigned char> bgra(static_cast<size_t>(info.width) * info.height * 4);
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    for (uint32_t y = 0; y < info.height; ++y) {
        auto src = static_cast<unsigned char*>(pixels) + y * info.stride;
        auto dst = bgra.data() + static_cast<size_t>(y) * info.width * 4;
        for (uint32_t x = 0; x < info.width; ++x) {
            // Android's bitmap is premultiplied; PDFium BGRA expects straight alpha.
            unsigned alpha = src[4*x+3];
            bool premul = (info.flags & ANDROID_BITMAP_FLAGS_ALPHA_MASK) == ANDROID_BITMAP_FLAGS_ALPHA_PREMUL;
            for (int c = 0; c < 3; ++c) {
                unsigned value = src[4*x+2-c];
                dst[4*x+c] = premul && alpha ? std::min(255u, (value * 255u + alpha/2) / alpha) : value;
            }
            dst[4*x+3] = alpha;
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    auto bmp = FPDFBitmap_CreateEx(info.width, info.height, FPDFBitmap_BGRA, bgra.data(), info.width * 4);
    if (!bmp) return JNI_FALSE;
    auto obj = FPDFPageObj_NewImageObj(doc->pdfDocument);
    bool ok = obj && FPDFImageObj_SetBitmap(NULL, 0, obj, bmp) &&
        FPDFImageObj_SetMatrix(obj, right-left, 0, 0, top-bottom, left, bottom);
    FPDFBitmap_Destroy(bmp);
    if (!ok) { if (obj) FPDFPageObj_Destroy(obj); return JNI_FALSE; }
    auto annot = FPDFPage_CreateAnnot(page, FPDF_ANNOT_STAMP);
    if (!annot) { FPDFPageObj_Destroy(obj); return JNI_FALSE; }
    FS_RECTF rect = {left, top, right, bottom};
    std::vector<FPDF_PAGEOBJECT> objects = {obj};
    return finishObjectAnnot(env, page, annot, rect, name, NULL, objects) ? JNI_TRUE : JNI_FALSE;
}

static jstring annotString(JNIEnv* env, FPDF_ANNOTATION annot, const char* key) {
    unsigned long bytes = FPDFAnnot_GetStringValue(annot, key, NULL, 0);
    std::vector<unsigned short> value(std::max(1ul, (bytes+1)/2), 0);
    if (bytes) FPDFAnnot_GetStringValue(annot, key, value.data(), bytes);
    return env->NewString(reinterpret_cast<const jchar*>(value.data()), bytes >= 2 ? bytes/2-1 : 0);
}

/** For each annotation of [page]: its metadata and name, and with [contents] its /Contents text. */
static jobjectArray readAnnots(JNIEnv* env, FPDF_PAGE page, bool contents) {
    int stride = contents ? 3 : 2;
    int count = page ? FPDFPage_GetAnnotCount(page) : 0;
    auto cls = env->FindClass("java/lang/String");
    auto result = env->NewObjectArray(std::max(0, count) * stride, cls, NULL);
    for (int i = 0; i < count; ++i) {
        auto annot = FPDFPage_GetAnnot(page, i);
        if (!annot) continue;
        FS_RECTF rect;
        if (FPDFAnnot_GetRect(annot, &rect)) {
            char values[256];
            snprintf(values, sizeof(values), "%d %d %.9g %.9g %.9g %.9g", i,
                FPDFAnnot_GetSubtype(annot), rect.left, rect.top, rect.right, rect.bottom);
            auto metadata = env->NewStringUTF(values);
            env->SetObjectArrayElement(result, i * stride, metadata);
            env->DeleteLocalRef(metadata);
            auto nm = annotString(env, annot, "NM");
            env->SetObjectArrayElement(result, i * stride + 1, nm);
            env->DeleteLocalRef(nm);
            if (contents) {
                auto text = annotString(env, annot, "Contents");
                env->SetObjectArrayElement(result, i * stride + 2, text);
                env->DeleteLocalRef(text);
            }
        }
        FPDFPage_CloseAnnot(annot);
    }
    return result;
}

JNI_FUNC(jobjectArray, PdfiumCore, nativeGetAnnots)(JNI_ARGS, jlong pagePtr) {
    return readAnnots(env, reinterpret_cast<FPDF_PAGE>(pagePtr), false);
}

/**
 * The annotations of page [pageIndex] with their text, read from the page loaded just for this
 * and closed again: listing a whole document does not keep its pages open.
 */
JNI_FUNC(jobjectArray, PdfiumCore, nativeReadAnnots)(JNI_ARGS, jlong docPtr, jint pageIndex) {
    auto doc = reinterpret_cast<DocumentFile*>(docPtr);
    FPDF_PAGE page = doc && doc->pdfDocument ? FPDF_LoadPage(doc->pdfDocument, pageIndex) : NULL;
    auto result = readAnnots(env, page, true);
    if (page) FPDF_ClosePage(page);
    return result;
}

/**
 * Puts the picture of the stamp annotation [name] at new bounds by changing its rectangle only:
 * the appearance stays as it is, a viewer fits its box (BBox) into the rectangle. The pixels are
 * not written again. Not every viewer scales the box, so before saving the caller writes the
 * picture again at its bounds.
 */
JNI_FUNC(jboolean, PdfiumCore, nativeMoveImageAnnot)(JNI_ARGS, jlong pagePtr, jstring name,
        jfloat left, jfloat top, jfloat right, jfloat bottom) {
    FPDF_PAGE page = reinterpret_cast<FPDF_PAGE>(pagePtr);
    if (!page || !name || !std::isfinite(left) || !std::isfinite(top) || !std::isfinite(right) ||
        !std::isfinite(bottom) || right <= left || top <= bottom) return JNI_FALSE;
    auto expected = annotName(env, name);
    int count = FPDFPage_GetAnnotCount(page);
    for (int i = 0; i < count; ++i) {
        FPDF_ANNOTATION annot = FPDFPage_GetAnnot(page, i);
        if (!annot) continue;
        unsigned long bytes = FPDFAnnot_GetStringValue(annot, "NM", NULL, 0);
        bool matches = false;
        if (bytes == expected.size() * sizeof(unsigned short)) {
            std::vector<unsigned short> value(expected.size(), 0);
            matches = FPDFAnnot_GetStringValue(annot, "NM", value.data(), bytes) == bytes && value == expected;
        }
        bool ok = false;
        if (matches) {
            auto obj = FPDFAnnot_GetObjectCount(annot) == 1 ? FPDFAnnot_GetObject(annot, 0) : NULL;
            FS_RECTF rect = {left, top, right, bottom};
            ok = obj && FPDFPageObj_GetType(obj) == FPDF_PAGEOBJ_IMAGE && FPDFAnnot_SetRect(annot, &rect);
        }
        FPDFPage_CloseAnnot(annot);
        if (matches) return ok ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

JNI_FUNC(jboolean, PdfiumCore, nativeRemoveAnnotAt)(JNI_ARGS, jlong pagePtr, jint index) {
    auto page = reinterpret_cast<FPDF_PAGE>(pagePtr);
    return page && index >= 0 && index < FPDFPage_GetAnnotCount(page) && FPDFPage_RemoveAnnot(page, index);
}

// Imported annotations can contain arbitrary dictionaries and appearance streams.
// Preserve the document for their undo instead of rebuilding a lossy approximation.
struct EditSnapshotWriter : FPDF_FILEWRITE {
    std::vector<unsigned char> bytes;
};

static int writeEditSnapshot(FPDF_FILEWRITE* self, const void* data, unsigned long size) {
    auto writer = static_cast<EditSnapshotWriter*>(self);
    if (size > static_cast<size_t>(INT32_MAX) - writer->bytes.size()) return 0;
    auto start = static_cast<const unsigned char*>(data);
    writer->bytes.insert(writer->bytes.end(), start, start + size);
    return 1;
}

JNI_FUNC(jbyteArray, PdfiumCore, nativeEditSnapshot)(JNI_ARGS, jlong docPtr) {
    auto doc = reinterpret_cast<DocumentFile*>(docPtr);
    if (!doc || !doc->pdfDocument) return NULL;
    EditSnapshotWriter writer;
    writer.version = 1;
    writer.WriteBlock = writeEditSnapshot;
    if (!FPDF_SaveAsCopy(doc->pdfDocument, &writer, FPDF_NO_INCREMENTAL | FPDF_REMOVE_SECURITY)) return NULL;
    auto result = env->NewByteArray(writer.bytes.size());
    if (result) env->SetByteArrayRegion(result, 0, writer.bytes.size(), reinterpret_cast<const jbyte*>(writer.bytes.data()));
    return result;
}

JNI_FUNC(jlong, PdfiumCore, nativeOpenEditSnapshot)(JNI_ARGS, jbyteArray snapshot) {
    if (!snapshot || !env->GetArrayLength(snapshot)) return 0;
    auto doc = new DocumentFile();
    doc->editSnapshotBytes.resize(env->GetArrayLength(snapshot));
    env->GetByteArrayRegion(snapshot, 0, doc->editSnapshotBytes.size(), reinterpret_cast<jbyte*>(doc->editSnapshotBytes.data()));
    doc->pdfDocument = FPDF_LoadMemDocument(doc->editSnapshotBytes.data(), doc->editSnapshotBytes.size(), NULL);
    if (!doc->pdfDocument) { delete doc; return 0; }
    return reinterpret_cast<jlong>(doc);
}

struct FileWriter : FPDF_FILEWRITE {
    FILE* file;
};

static int writeBlock(FPDF_FILEWRITE* self, const void* data, unsigned long size) {
    FileWriter* writer = static_cast<FileWriter*>(self);
    return fwrite(data, 1, size, writer->file) == size ? 1 : 0;
}

static bool saveDocumentToPath(JNIEnv *env, FPDF_DOCUMENT document, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, NULL);
    FILE* file = fopen(cpath, "wb");
    env->ReleaseStringUTFChars(path, cpath);
    if (file == NULL) {
        LOGE("Cannot open file to save. Error:%d", errno);
        return false;
    }
    FileWriter writer;
    writer.version = 1;
    writer.WriteBlock = &writeBlock;
    writer.file = file;
    bool ok = FPDF_SaveAsCopy(document, &writer, FPDF_NO_INCREMENTAL);
    ok = (fflush(file) == 0) && ok;
    ok = (fsync(fileno(file)) == 0) && ok;
    ok = (fclose(file) == 0) && ok;
    return ok;
}

/**
 * Save the whole document, with its in memory changes, to a new file at path.
 */
JNI_FUNC(jboolean, PdfiumCore, nativeSaveAsCopy)(JNI_ARGS, jlong docPtr, jstring path) {
    DocumentFile* doc = reinterpret_cast<DocumentFile*>(docPtr);
    if (doc == NULL || doc->pdfDocument == NULL || path == NULL) {
        return JNI_FALSE;
    }
    return saveDocumentToPath(env, doc->pdfDocument, path) ? JNI_TRUE : JNI_FALSE;
}

/**
 * Create an empty document, to be filled with nativeImportPages or nativeAddJpegPage.
 */
JNI_FUNC(jlong, PdfiumCore, nativeCreateDocument)(JNI_ARGS) {
    DocumentFile* doc = new DocumentFile();
    doc->pdfDocument = FPDF_CreateNewDocument();
    if (doc->pdfDocument == NULL) {
        delete doc;
        return 0;
    }
    return reinterpret_cast<jlong>(doc);
}

/**
 * Copy pages of src into dest as PDF objects, so text, links and vector content are kept.
 * pageIndices are zero based, in output order; NULL copies every page. insertIndex < 0 appends.
 * The copies do not reference src, so it can be closed right after.
 */
JNI_FUNC(jboolean, PdfiumCore, nativeImportPages)(JNI_ARGS, jlong destPtr, jlong srcPtr,
                                                  jintArray pageIndices, jint insertIndex) {
    DocumentFile* dest = reinterpret_cast<DocumentFile*>(destPtr);
    DocumentFile* src = reinterpret_cast<DocumentFile*>(srcPtr);
    if (dest == NULL || dest->pdfDocument == NULL || src == NULL || src->pdfDocument == NULL) {
        return JNI_FALSE;
    }
    int index = insertIndex < 0 ? FPDF_GetPageCount(dest->pdfDocument) : insertIndex;
    if (pageIndices == NULL) {
        return FPDF_ImportPagesByIndex(dest->pdfDocument, src->pdfDocument, NULL, 0, index) ? JNI_TRUE : JNI_FALSE;
    }
    jsize count = env->GetArrayLength(pageIndices);
    if (count <= 0) return JNI_FALSE;
    std::vector<int> indices(count);
    env->GetIntArrayRegion(pageIndices, 0, count, reinterpret_cast<jint*>(indices.data()));
    return FPDF_ImportPagesByIndex(dest->pdfDocument, src->pdfDocument, indices.data(), count, index)
            ? JNI_TRUE : JNI_FALSE;
}

struct MemoryAccess {
    const unsigned char* data;
    unsigned long size;
};

static int readMemoryBlock(void* param, unsigned long position, unsigned char* outBuffer, unsigned long size) {
    MemoryAccess* memory = static_cast<MemoryAccess*>(param);
    if (position > memory->size || size > memory->size - position) return 0;
    memcpy(outBuffer, memory->data + position, size);
    return 1;
}

/**
 * Append a page of width x height points showing the JPEG stretched over the whole page.
 * The JPEG bytes are stored as they are (no re-encoding), which keeps the file small.
 */
JNI_FUNC(jboolean, PdfiumCore, nativeAddJpegPage)(JNI_ARGS, jlong docPtr, jbyteArray jpeg,
                                                  jfloat width, jfloat height) {
    DocumentFile* doc = reinterpret_cast<DocumentFile*>(docPtr);
    if (doc == NULL || doc->pdfDocument == NULL || jpeg == NULL || width <= 0 || height <= 0) {
        return JNI_FALSE;
    }
    jsize length = env->GetArrayLength(jpeg);
    if (length <= 0) return JNI_FALSE;
    std::vector<unsigned char> bytes(length);
    env->GetByteArrayRegion(jpeg, 0, length, reinterpret_cast<jbyte*>(bytes.data()));

    MemoryAccess memory = { bytes.data(), static_cast<unsigned long>(length) };
    FPDF_FILEACCESS access;
    access.m_FileLen = memory.size;
    access.m_GetBlock = &readMemoryBlock;
    access.m_Param = &memory;

    int index = FPDF_GetPageCount(doc->pdfDocument);
    FPDF_PAGE page = FPDFPage_New(doc->pdfDocument, index, width, height);
    if (page == NULL) return JNI_FALSE;

    FPDF_PAGEOBJECT image = FPDFPageObj_NewImageObj(doc->pdfDocument);
    // Inline: the JPEG is copied into the document, so bytes can go when this returns.
    bool ok = image != NULL
            && FPDFImageObj_LoadJpegFileInline(&page, 1, image, &access)
            && FPDFImageObj_SetMatrix(image, width, 0, 0, height, 0, 0);
    if (ok) {
        FPDFPage_InsertObject(page, image); // the page owns the image from here
        ok = FPDFPage_GenerateContent(page);
    } else if (image != NULL) {
        FPDFPageObj_Destroy(image);
    }
    FPDF_ClosePage(page);
    if (!ok) FPDFPage_Delete(doc->pdfDocument, index);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// FPDF_PageToDevice only outputs ints, so points are mapped onto a grid 100 times the page
// size to keep 0.01 point precision.
static const double kGrid = 100.0;

/** A rectangle of the page in points, origin at the top left of the page as it is rendered. */
static void toShown(FPDF_PAGE page, float left, float top, float right, float bottom, jfloat* out) {
    int sizeX = (int) lround(FPDF_GetPageWidthF(page) * kGrid);
    int sizeY = (int) lround(FPDF_GetPageHeightF(page) * kGrid);
    int x1, y1, x2, y2;
    FPDF_PageToDevice(page, 0, 0, sizeX, sizeY, 0, left, top, &x1, &y1);
    FPDF_PageToDevice(page, 0, 0, sizeX, sizeY, 0, right, bottom, &x2, &y2);
    // with /Rotate the corners swap, so sort them
    out[0] = (jfloat) (std::min(x1, x2) / kGrid);
    out[1] = (jfloat) (std::min(y1, y2) / kGrid);
    out[2] = (jfloat) (std::max(x1, x2) / kGrid);
    out[3] = (jfloat) (std::max(y1, y2) / kGrid);
}

/** Code point and box (4 floats, 0 when none) of every character of [textPage]. */
static void readTextLayout(FPDF_PAGE page, FPDF_TEXTPAGE textPage, std::vector<jint>& codePoints, std::vector<jfloat>& boxes) {
    int count = std::max(0, FPDFText_CountChars(textPage));
    codePoints.assign(count, 0);
    boxes.assign((size_t) count * 4, 0.0f);
    for (int i = 0; i < count; i++) {
        codePoints[i] = (jint) FPDFText_GetUnicode(textPage, i);
        FS_RECTF rect;
        if (!FPDFText_GetLooseCharBox(textPage, i, &rect) || rect.right <= rect.left || rect.top <= rect.bottom) continue;
        toShown(page, rect.left, rect.top, rect.right, rect.bottom, &boxes[(size_t) i * 4]);
    }
}

/**
 * Size, style and color of every character of [textPage]: size in points (0 unknown);
 * style = weight (0 unknown) | 1 << 16 italic | 1 << 17 force bold | font index << 18 (index
 * into [fonts] + 1, 0 none); color ARGB (0 unknown). Fonts keep their subset prefix.
 */
static void readTextStyles(FPDF_TEXTPAGE textPage, std::vector<jfloat>& sizes, std::vector<jint>& styles,
                           std::vector<jint>& colors, std::vector<std::string>& fonts) {
    int count = std::max(0, FPDFText_CountChars(textPage));
    sizes.assign(count, 0.0f);
    styles.assign(count, 0);
    colors.assign(count, 0);
    std::map<std::string, int> fontIndex;
    char name[256];
    for (int i = 0; i < count; i++) {
        sizes[i] = (jfloat) FPDFText_GetFontSize(textPage, i);
        int weight = std::max(0, FPDFText_GetFontWeight(textPage, i));
        int flags = 0;
        unsigned long length = FPDFText_GetFontInfo(textPage, i, name, sizeof(name), &flags);
        int font = 0;
        if (length > 0 && length <= sizeof(name)) {
            std::string key(name);
            auto found = fontIndex.find(key);
            if (found == fontIndex.end()) {
                fonts.push_back(key);
                font = (int) fonts.size();
                fontIndex[key] = font;
            } else {
                font = found->second;
            }
        }
        // PDF font descriptor flags: bit 7 italic, bit 19 force bold
        styles[i] = (jint) ((weight & 0xFFFF) | ((flags & (1 << 6)) ? (1 << 16) : 0)
                | ((flags & (1 << 18)) ? (1 << 17) : 0) | (std::min(font, 8191) << 18));
        unsigned int r = 0, g = 0, b = 0, a = 0;
        if (FPDFText_GetFillColor(textPage, i, &r, &g, &b, &a)) {
            colors[i] = (jint) ((std::max(1u, a) << 24) | (r << 16) | (g << 8) | b);
        }
    }
}

/**
 * Pictures placed directly on the page, 11 floats each: {object index, pixel width, pixel height,
 * shown width, shown height (points), stored bytes, left, top, right, bottom (shown box),
 * transparent}. With [checkTransparency] each picture is drawn to find out whether it has
 * transparency, and transparent ones are left out; without, the flag is 1 (unknown) for all.
 */
static void readPageImages(FPDF_DOCUMENT document, FPDF_PAGE page, bool checkTransparency, std::vector<jfloat>& values) {
    int count = FPDFPage_CountObjects(page);
    for (int i = 0; i < count; ++i) {
        FPDF_PAGEOBJECT obj = FPDFPage_GetObject(page, i);
        if (!obj || FPDFPageObj_GetType(obj) != FPDF_PAGEOBJ_IMAGE) continue;
        unsigned int w = 0, h = 0;
        if (!FPDFImageObj_GetImagePixelSize(obj, &w, &h) || w < 16 || h < 16) continue;
        float l, b, r, t;
        if (!FPDFPageObj_GetBounds(obj, &l, &b, &r, &t)) continue;
        if (checkTransparency) {
            // a picture with a mask would lose its transparency when stored as JPEG
            FPDF_BITMAP shown = FPDFImageObj_GetRenderedBitmap(document, page, obj);
            bool transparent = !shown;
            if (shown) {
                if (FPDFBitmap_GetFormat(shown) == FPDFBitmap_BGRA) {
                    int bw = FPDFBitmap_GetWidth(shown), bh = FPDFBitmap_GetHeight(shown), stride = FPDFBitmap_GetStride(shown);
                    auto px = static_cast<unsigned char*>(FPDFBitmap_GetBuffer(shown));
                    for (int y = 0; y < bh && !transparent; ++y)
                        for (int x = 0; x < bw; ++x) if (px[y * stride + x * 4 + 3] != 255) { transparent = true; break; }
                }
                FPDFBitmap_Destroy(shown);
            }
            if (transparent) continue;
        }
        unsigned long stored = FPDFImageObj_GetImageDataRaw(obj, NULL, 0);
        jfloat box[4];
        toShown(page, l, t, r, b, box);
        values.insert(values.end(), {(float) i, (float) w, (float) h, r - l, t - b, (float) stored,
                box[0], box[1], box[2], box[3], checkTransparency ? 0.0f : 1.0f});
    }
}

static jobjectArray stringArray(JNIEnv* env, const std::vector<std::string>& values) {
    jclass stringClass = env->FindClass("java/lang/String");
    if (!stringClass) return NULL;
    jobjectArray array = env->NewObjectArray((jsize) values.size(), stringClass, NULL);
    if (!array) return NULL;
    for (size_t i = 0; i < values.size(); i++) {
        jstring value = env->NewStringUTF(values[i].c_str());
        if (value == NULL) { env->ExceptionClear(); continue; } // not modified UTF-8
        env->SetObjectArrayElement(array, (jsize) i, value);
        env->DeleteLocalRef(value);
    }
    return array;
}

static jintArray intArray(JNIEnv* env, const std::vector<jint>& v) {
    jintArray a = env->NewIntArray((jsize) v.size());
    if (a) env->SetIntArrayRegion(a, 0, (jsize) v.size(), v.data());
    return a;
}

static jfloatArray floatArray(JNIEnv* env, const std::vector<jfloat>& v) {
    jfloatArray a = env->NewFloatArray((jsize) v.size());
    if (a) env->SetFloatArrayRegion(a, 0, (jsize) v.size(), v.data());
    return a;
}

/** An Object[] of [items], or NULL when one of them could not be made. */
static jobjectArray objectArray(JNIEnv* env, std::initializer_list<jobject> items) {
    jclass objectClass = env->FindClass("java/lang/Object");
    if (!objectClass) return NULL;
    for (jobject item : items) if (item == NULL) return NULL;
    jobjectArray result = env->NewObjectArray((jsize) items.size(), objectClass, NULL);
    if (!result) return NULL;
    jsize i = 0;
    for (jobject item : items) env->SetObjectArrayElement(result, i++, item);
    return result;
}

/**
 * Text of a page with the position of every character, in one call.
 * Returns {int[] codePoints, float[] boxes, float[] {pageWidth, pageHeight}}; boxes holds
 * left, top, right, bottom per character in points, origin at the top left of the page as it
 * is rendered (crop box and /Rotate applied). Characters without a box get 0,0,0,0.
 */
JNI_FUNC(jobjectArray, PdfiumCore, nativeGetPageTextLayout)(JNI_ARGS, jlong docPtr, jint pageIndex) {
    DocumentFile* doc = reinterpret_cast<DocumentFile*>(docPtr);
    if (doc == NULL || doc->pdfDocument == NULL) return NULL;
    FPDF_PAGE page = FPDF_LoadPage(doc->pdfDocument, pageIndex);
    if (page == NULL) return NULL;
    FPDF_TEXTPAGE textPage = FPDFText_LoadPage(page);
    if (textPage == NULL) {
        FPDF_ClosePage(page);
        return NULL;
    }
    std::vector<jint> codePoints;
    std::vector<jfloat> boxes;
    readTextLayout(page, textPage, codePoints, boxes);
    std::vector<jfloat> size = { FPDF_GetPageWidthF(page), FPDF_GetPageHeightF(page) };
    FPDFText_ClosePage(textPage);
    FPDF_ClosePage(page);
    return objectArray(env, { intArray(env, codePoints), floatArray(env, boxes), floatArray(env, size) });
}

/**
 * Everything PDF → Word needs of a page, with one load of it: {int[] codePoints, float[] boxes,
 * float[] {pageWidth, pageHeight}} as nativeGetPageTextLayout, then {float[] sizes, int[] styles,
 * int[] colors, String[] fonts} as readTextStyles, then float[] pictures as readPageImages
 * (transparency not checked).
 */
JNI_FUNC(jobjectArray, PdfiumCore, nativeGetPageForWord)(JNI_ARGS, jlong docPtr, jint pageIndex) {
    DocumentFile* doc = reinterpret_cast<DocumentFile*>(docPtr);
    if (doc == NULL || doc->pdfDocument == NULL) return NULL;
    FPDF_PAGE page = FPDF_LoadPage(doc->pdfDocument, pageIndex);
    if (page == NULL) return NULL;
    FPDF_TEXTPAGE textPage = FPDFText_LoadPage(page);
    if (textPage == NULL) {
        FPDF_ClosePage(page);
        return NULL;
    }
    std::vector<jint> codePoints, styles, colors;
    std::vector<jfloat> boxes, sizes, pictures;
    std::vector<std::string> fonts;
    readTextLayout(page, textPage, codePoints, boxes);
    readTextStyles(textPage, sizes, styles, colors, fonts);
    readPageImages(doc->pdfDocument, page, false, pictures);
    std::vector<jfloat> size = { FPDF_GetPageWidthF(page), FPDF_GetPageHeightF(page) };
    FPDFText_ClosePage(textPage);
    FPDF_ClosePage(page);
    return objectArray(env, { intArray(env, codePoints), floatArray(env, boxes), floatArray(env, size),
            floatArray(env, sizes), intArray(env, styles), intArray(env, colors), stringArray(env, fonts), floatArray(env, pictures) });
}

// ---- page edits ---------------------------------------------------------------------------

/** Sets the /Rotate of a page: 0, 1, 2, 3 for 0, 90, 180, 270 degrees clockwise. */
JNI_FUNC(jboolean, PdfiumCore, nativeSetPageRotation)(JNI_ARGS, jlong docPtr, jint pageIndex, jint rotation) {
    auto doc = reinterpret_cast<DocumentFile*>(docPtr);
    if (!doc || !doc->pdfDocument) return JNI_FALSE;
    FPDF_PAGE page = FPDF_LoadPage(doc->pdfDocument, pageIndex);
    if (!page) return JNI_FALSE;
    FPDFPage_SetRotation(page, ((rotation % 4) + 4) % 4);
    FPDF_ClosePage(page);
    return JNI_TRUE;
}

JNI_FUNC(jint, PdfiumCore, nativeGetPageRotation)(JNI_ARGS, jlong docPtr, jint pageIndex) {
    auto doc = reinterpret_cast<DocumentFile*>(docPtr);
    if (!doc || !doc->pdfDocument) return 0;
    FPDF_PAGE page = FPDF_LoadPage(doc->pdfDocument, pageIndex);
    if (!page) return 0;
    int rotation = FPDFPage_GetRotation(page);
    FPDF_ClosePage(page);
    return rotation;
}

/** Inserts an empty page of width x height points at index (< 0 appends). */
JNI_FUNC(jboolean, PdfiumCore, nativeInsertBlankPage)(JNI_ARGS, jlong docPtr, jint index, jfloat width, jfloat height) {
    auto doc = reinterpret_cast<DocumentFile*>(docPtr);
    if (!doc || !doc->pdfDocument || !(width > 0) || !(height > 0)) return JNI_FALSE;
    int at = index < 0 ? FPDF_GetPageCount(doc->pdfDocument) : index;
    FPDF_PAGE page = FPDFPage_New(doc->pdfDocument, at, width, height);
    if (!page) return JNI_FALSE;
    bool ok = FPDFPage_GenerateContent(page);
    FPDF_ClosePage(page);
    return ok ? JNI_TRUE : JNI_FALSE;
}

/** Deletes a page. */
JNI_FUNC(jboolean, PdfiumCore, nativeDeletePage)(JNI_ARGS, jlong docPtr, jint index) {
    auto doc = reinterpret_cast<DocumentFile*>(docPtr);
    if (!doc || !doc->pdfDocument || index < 0 || index >= FPDF_GetPageCount(doc->pdfDocument)) return JNI_FALSE;
    FPDFPage_Delete(doc->pdfDocument, index);
    return JNI_TRUE;
}

/** Makes the annotations and form fields of every page part of its content (they can no longer be edited). */
JNI_FUNC(jboolean, PdfiumCore, nativeFlattenPage)(JNI_ARGS, jlong docPtr, jint pageIndex) {
    auto doc = reinterpret_cast<DocumentFile*>(docPtr);
    if (!doc || !doc->pdfDocument) return JNI_FALSE;
    FPDF_PAGE page = FPDF_LoadPage(doc->pdfDocument, pageIndex);
    if (!page) return JNI_FALSE;
    int result = FPDFPage_Flatten(page, FLAT_PRINT);
    FPDF_ClosePage(page);
    return result == FLATTEN_FAIL ? JNI_FALSE : JNI_TRUE;
}

/** Saves without the encryption of the document (it was opened with its password). */
JNI_FUNC(jboolean, PdfiumCore, nativeSaveWithoutSecurity)(JNI_ARGS, jlong docPtr, jstring path) {
    auto doc = reinterpret_cast<DocumentFile*>(docPtr);
    if (!doc || !doc->pdfDocument || !path) return JNI_FALSE;
    const char* cpath = env->GetStringUTFChars(path, NULL);
    FILE* file = fopen(cpath, "wb");
    env->ReleaseStringUTFChars(path, cpath);
    if (!file) return JNI_FALSE;
    FileWriter writer;
    writer.version = 1;
    writer.WriteBlock = &writeBlock;
    writer.file = file;
    bool ok = FPDF_SaveAsCopy(doc->pdfDocument, &writer, FPDF_NO_INCREMENTAL | FPDF_REMOVE_SECURITY);
    ok = (fflush(file) == 0) && ok;
    ok = (fclose(file) == 0) && ok;
    return ok ? JNI_TRUE : JNI_FALSE;
}

// ---- text in the page content: watermark, page numbers, OCR layer --------------------------

/**
 * One line of text in the content of a page. The anchor point (x, y, in page points, origin at
 * the bottom left of the unrotated page) is where the text's anchor goes: anchor 0 left of the
 * baseline, 1 its center, 2 right. The text turns angle degrees counter-clockwise around it.
 * Returns {left, top, right, bottom} of the text, or NULL.
 */
JNI_FUNC(jfloatArray, PdfiumCore, nativeAddPageText)(JNI_ARGS, jlong docPtr, jint pageIndex, jstring fontPath,
        jstring text, jfloat size, jint r, jint g, jint b, jint a, jfloat x, jfloat y, jfloat angle, jint anchor) {
    auto doc = reinterpret_cast<DocumentFile*>(docPtr);
    if (!doc || !doc->pdfDocument || !text || !(size > 0)) return NULL;
    FPDF_FONT font = editFont(doc, env, fontPath);
    if (!font) return NULL;
    FPDF_PAGE page = FPDF_LoadPage(doc->pdfDocument, pageIndex);
    if (!page) return NULL;
    auto value = annotName(env, text);
    FPDF_PAGEOBJECT obj = FPDFPageObj_CreateTextObj(doc->pdfDocument, font, size);
    bool ok = obj && FPDFText_SetText(obj, value.data()) && FPDFPageObj_SetFillColor(obj, r, g, b, a);
    float l = 0, bt = 0, rt = 0, tp = 0;
    ok = ok && FPDFPageObj_GetBounds(obj, &l, &bt, &rt, &tp);
    jfloatArray result = NULL;
    if (ok) {
        float w = rt - l;
        float dx = anchor == 1 ? -w / 2 : anchor == 2 ? -w : 0;
        float dy = anchor == 1 ? -size * 0.35f : 0;
        double rad = angle * M_PI / 180.0;
        float c = (float) cos(rad), sn = (float) sin(rad);
        // move the anchor to the origin, turn, then put it at (x, y)
        FPDFPageObj_Transform(obj, 1, 0, 0, 1, dx - l, dy);
        FPDFPageObj_Transform(obj, c, sn, -sn, c, x, y);
        FPDFPage_InsertObject(page, obj);
        ok = FPDFPage_GenerateContent(page);
        FPDFPageObj_GetBounds(obj, &l, &bt, &rt, &tp);
        jfloat values[] = {l, tp, rt, bt};
        result = env->NewFloatArray(4);
        env->SetFloatArrayRegion(result, 0, 4, values);
    } else if (obj) {
        FPDFPageObj_Destroy(obj);
    }
    FPDF_ClosePage(page);
    return ok ? result : NULL;
}

/**
 * Invisible words over a scanned page, so its text can be found, selected and copied: each word
 * is stretched to its box (left, bottom, right, top in page points, 4 floats per word). On a page
 * shown turned [turns] quarters clockwise, the words run the way they are read on screen.
 */
JNI_FUNC(jboolean, PdfiumCore, nativeAddInvisibleWords)(JNI_ARGS, jlong docPtr, jint pageIndex, jstring fontPath,
        jobjectArray words, jfloatArray boxes, jint turns) {
    auto doc = reinterpret_cast<DocumentFile*>(docPtr);
    if (!doc || !doc->pdfDocument || !words || !boxes) return JNI_FALSE;
    jsize count = env->GetArrayLength(words);
    if (env->GetArrayLength(boxes) != count * 4) return JNI_FALSE;
    FPDF_FONT font = editFont(doc, env, fontPath);
    if (!font) return JNI_FALSE;
    FPDF_PAGE page = FPDF_LoadPage(doc->pdfDocument, pageIndex);
    if (!page) return JNI_FALSE;
    std::vector<jfloat> box(count * 4);
    env->GetFloatArrayRegion(boxes, 0, count * 4, box.data());
    int added = 0;
    int q = ((turns % 4) + 4) % 4;
    for (jsize i = 0; i < count; ++i) {
        auto word = (jstring) env->GetObjectArrayElement(words, i);
        float left = box[i * 4], bottom = box[i * 4 + 1], right = box[i * 4 + 2], top = box[i * 4 + 3];
        // along: the length of the word as read; across: its height
        float along = q % 2 == 0 ? right - left : top - bottom;
        float across = q % 2 == 0 ? top - bottom : right - left;
        if (!word || !(along > 0) || !(across > 0)) { if (word) env->DeleteLocalRef(word); continue; }
        auto value = annotName(env, word);
        env->DeleteLocalRef(word);
        FPDF_PAGEOBJECT obj = FPDFPageObj_CreateTextObj(doc->pdfDocument, font, across);
        if (!obj) continue;
        float l, b, r, t;
        // a word with a space after it (the next word follows on the line): the word alone is
        // fitted to its box, the space runs on into the gap after it
        size_t len = value.size();
        while (len > 0 && value[len - 1] == 0) --len;
        bool spaced = len > 1 && value[len - 1] == ' ';
        std::vector<unsigned short> bare(value.begin(), value.begin() + len - (spaced ? 1 : 0));
        bare.push_back(0);
        if (!FPDFText_SetText(obj, bare.data()) || !FPDFTextObj_SetTextRenderMode(obj, FPDF_TEXTRENDERMODE_INVISIBLE) ||
            !FPDFPageObj_GetBounds(obj, &l, &b, &r, &t) || !(r > l) || (spaced && !FPDFText_SetText(obj, value.data()))) {
            FPDFPageObj_Destroy(obj);
            continue;
        }
        // stretched to its length, the start of its baseline at the origin
        float sx = along / (r - l);
        FPDFPageObj_Transform(obj, sx, 0, 0, 1, -l * sx, 0);
        // turned as the page is shown, then its baseline (a fifth of the height above the
        // bottom of the word as read) put in place
        float base = across * 0.2f;
        float ox, oy, c, sn;
        switch (q) {
            case 1: ox = right - base; oy = bottom; c = 0; sn = 1; break;   // reads upward
            case 2: ox = right; oy = top - base; c = -1; sn = 0; break;     // reads leftward
            case 3: ox = left + base; oy = top; c = 0; sn = -1; break;      // reads downward
            default: ox = left; oy = bottom + base; c = 1; sn = 0; break;
        }
        FPDFPageObj_Transform(obj, c, sn, -sn, c, ox, oy);
        FPDFPage_InsertObject(page, obj);
        ++added;
    }
    bool ok = added == 0 || FPDFPage_GenerateContent(page);
    FPDF_ClosePage(page);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// ---- pictures of a page (compression) -------------------------------------------------------

/** Opaque pictures placed directly on the page, as readPageImages. */
JNI_FUNC(jfloatArray, PdfiumCore, nativeGetPageImages)(JNI_ARGS, jlong docPtr, jint pageIndex) {
    auto doc = reinterpret_cast<DocumentFile*>(docPtr);
    if (!doc || !doc->pdfDocument) return NULL;
    FPDF_PAGE page = FPDF_LoadPage(doc->pdfDocument, pageIndex);
    if (!page) return NULL;
    std::vector<jfloat> values;
    readPageImages(doc->pdfDocument, page, true, values);
    FPDF_ClosePage(page);
    return floatArray(env, values);
}

/**
 * Picture [objIndex] as it is shown, its mask applied: {width, height, then ARGB per pixel},
 * or null. Pictures over [maxPixels] are refused.
 */
JNI_FUNC(jintArray, PdfiumCore, nativeGetRenderedImage)(JNI_ARGS, jlong docPtr, jint pageIndex, jint objIndex, jint maxPixels) {
    auto doc = reinterpret_cast<DocumentFile*>(docPtr);
    if (!doc || !doc->pdfDocument) return NULL;
    FPDF_PAGE page = FPDF_LoadPage(doc->pdfDocument, pageIndex);
    if (!page) return NULL;
    FPDF_PAGEOBJECT obj = FPDFPage_GetObject(page, objIndex);
    jintArray result = NULL;
    unsigned int pw = 0, ph = 0;
    if (obj && FPDFPageObj_GetType(obj) == FPDF_PAGEOBJ_IMAGE && FPDFImageObj_GetImagePixelSize(obj, &pw, &ph)
            && (long long) pw * ph <= maxPixels) {
        FPDF_BITMAP shown = FPDFImageObj_GetRenderedBitmap(doc->pdfDocument, page, obj);
        if (shown) {
            int w = FPDFBitmap_GetWidth(shown), h = FPDFBitmap_GetHeight(shown), stride = FPDFBitmap_GetStride(shown);
            int format = FPDFBitmap_GetFormat(shown);
            auto px = static_cast<unsigned char*>(FPDFBitmap_GetBuffer(shown));
            if (w > 0 && h > 0 && (long long) w * h <= maxPixels && px &&
                    (format == FPDFBitmap_BGRA || format == FPDFBitmap_BGRx || format == FPDFBitmap_BGR || format == FPDFBitmap_Gray)) {
                std::vector<jint> out((size_t) w * h + 2);
                out[0] = w; out[1] = h;
                for (int y = 0; y < h; ++y) {
                    auto row = px + (size_t) y * stride;
                    for (int x = 0; x < w; ++x) {
                        unsigned int r, g, b, a = 255;
                        if (format == FPDFBitmap_Gray) { r = g = b = row[x]; }
                        else if (format == FPDFBitmap_BGR) { b = row[x * 3]; g = row[x * 3 + 1]; r = row[x * 3 + 2]; }
                        else { b = row[x * 4]; g = row[x * 4 + 1]; r = row[x * 4 + 2]; if (format == FPDFBitmap_BGRA) a = row[x * 4 + 3]; }
                        out[2 + (size_t) y * w + x] = (jint) ((a << 24) | (r << 16) | (g << 8) | b);
                    }
                }
                result = env->NewIntArray((jsize) out.size());
                if (result) env->SetIntArrayRegion(result, 0, (jsize) out.size(), out.data());
            }
            FPDFBitmap_Destroy(shown);
        }
    }
    FPDF_ClosePage(page);
    return result;
}

/** The pixels of picture [objIndex] into [bitmap] (ARGB_8888 of its pixel size). */
JNI_FUNC(jboolean, PdfiumCore, nativeGetImagePixels)(JNI_ARGS, jlong docPtr, jint pageIndex, jint objIndex, jobject bitmap) {
    auto doc = reinterpret_cast<DocumentFile*>(docPtr);
    if (!doc || !doc->pdfDocument || !bitmap) return JNI_FALSE;
    FPDF_PAGE page = FPDF_LoadPage(doc->pdfDocument, pageIndex);
    if (!page) return JNI_FALSE;
    FPDF_PAGEOBJECT obj = FPDFPage_GetObject(page, objIndex);
    FPDF_BITMAP image = obj && FPDFPageObj_GetType(obj) == FPDF_PAGEOBJ_IMAGE ? FPDFImageObj_GetBitmap(obj) : NULL;
    bool ok = false;
    AndroidBitmapInfo info;
    void* pixels = NULL;
    if (image && AndroidBitmap_getInfo(env, bitmap, &info) == ANDROID_BITMAP_RESULT_SUCCESS &&
        info.format == ANDROID_BITMAP_FORMAT_RGBA_8888 &&
        (int) info.width == FPDFBitmap_GetWidth(image) && (int) info.height == FPDFBitmap_GetHeight(image) &&
        AndroidBitmap_lockPixels(env, bitmap, &pixels) == ANDROID_BITMAP_RESULT_SUCCESS) {
        int format = FPDFBitmap_GetFormat(image);
        int stride = FPDFBitmap_GetStride(image);
        auto src = static_cast<unsigned char*>(FPDFBitmap_GetBuffer(image));
        ok = format == FPDFBitmap_Gray || format == FPDFBitmap_BGR || format == FPDFBitmap_BGRx || format == FPDFBitmap_BGRA;
        for (uint32_t y = 0; ok && y < info.height; ++y) {
            auto row = src + y * stride;
            auto dst = static_cast<unsigned char*>(pixels) + y * info.stride;
            for (uint32_t x = 0; x < info.width; ++x) {
                unsigned char rr, gg, bb;
                if (format == FPDFBitmap_Gray) { rr = gg = bb = row[x]; }
                else if (format == FPDFBitmap_BGR) { bb = row[x * 3]; gg = row[x * 3 + 1]; rr = row[x * 3 + 2]; }
                else { bb = row[x * 4]; gg = row[x * 4 + 1]; rr = row[x * 4 + 2]; }
                dst[x * 4] = rr; dst[x * 4 + 1] = gg; dst[x * 4 + 2] = bb; dst[x * 4 + 3] = 255;
            }
        }
        AndroidBitmap_unlockPixels(env, bitmap);
    }
    if (image) FPDFBitmap_Destroy(image);
    FPDF_ClosePage(page);
    return ok ? JNI_TRUE : JNI_FALSE;
}

/** Stores [jpeg] as the data of picture [objIndex]; its place and size on the page do not change. */
JNI_FUNC(jboolean, PdfiumCore, nativeReplaceImageJpeg)(JNI_ARGS, jlong docPtr, jint pageIndex, jint objIndex, jbyteArray jpeg) {
    auto doc = reinterpret_cast<DocumentFile*>(docPtr);
    if (!doc || !doc->pdfDocument || !jpeg) return JNI_FALSE;
    jsize length = env->GetArrayLength(jpeg);
    if (length <= 0) return JNI_FALSE;
    std::vector<unsigned char> bytes(length);
    env->GetByteArrayRegion(jpeg, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
    FPDF_PAGE page = FPDF_LoadPage(doc->pdfDocument, pageIndex);
    if (!page) return JNI_FALSE;
    FPDF_PAGEOBJECT obj = FPDFPage_GetObject(page, objIndex);
    MemoryAccess memory = { bytes.data(), static_cast<unsigned long>(length) };
    FPDF_FILEACCESS access;
    access.m_FileLen = memory.size;
    access.m_GetBlock = &readMemoryBlock;
    access.m_Param = &memory;
    bool ok = obj && FPDFPageObj_GetType(obj) == FPDF_PAGEOBJ_IMAGE &&
        FPDFImageObj_LoadJpegFileInline(&page, 1, obj, &access) && FPDFPage_GenerateContent(page);
    FPDF_ClosePage(page);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// ---- notes and shapes ---------------------------------------------------------------------

/** A sticky note (Text annotation) with its icon at x, y (top left, page points). */
JNI_FUNC(jboolean, PdfiumCore, nativeAddNoteAnnot)(JNI_ARGS, jlong pagePtr, jfloat x, jfloat y,
        jint r, jint g, jint b, jint a, jstring contents, jstring name) {
    auto page = reinterpret_cast<FPDF_PAGE>(pagePtr);
    if (!page || !name || !std::isfinite(x) || !std::isfinite(y)) return JNI_FALSE;
    auto annot = FPDFPage_CreateAnnot(page, FPDF_ANNOT_TEXT);
    if (!annot) return JNI_FALSE;
    FS_RECTF rect = {x, y, x + 20, y - 20};
    auto nm = annotName(env, name);
    bool ok = FPDFAnnot_SetRect(annot, &rect) && FPDFAnnot_SetColor(annot, FPDFANNOT_COLORTYPE_Color, r, g, b, a)
        && FPDFAnnot_SetFlags(annot, FPDF_ANNOT_FLAG_PRINT | FPDF_ANNOT_FLAG_NOZOOM | FPDF_ANNOT_FLAG_NOROTATE)
        && FPDFAnnot_SetStringValue(annot, "NM", nm.data());
    if (ok && contents) {
        auto text = annotName(env, contents);
        ok = FPDFAnnot_SetStringValue(annot, "Contents", text.data());
    }
    int index = ok ? -1 : FPDFPage_GetAnnotIndex(page, annot);
    FPDFPage_CloseAnnot(annot);
    if (!ok && index >= 0) FPDFPage_RemoveAnnot(page, index);
    return ok ? JNI_TRUE : JNI_FALSE;
}

/** The /Contents text of annotation [index] (a note, a comment), or null. */
JNI_FUNC(jstring, PdfiumCore, nativeGetAnnotContents)(JNI_ARGS, jlong pagePtr, jint index) {
    auto page = reinterpret_cast<FPDF_PAGE>(pagePtr);
    if (!page || index < 0 || index >= FPDFPage_GetAnnotCount(page)) return NULL;
    auto annot = FPDFPage_GetAnnot(page, index);
    if (!annot) return NULL;
    unsigned long bytes = FPDFAnnot_GetStringValue(annot, "Contents", NULL, 0);
    jstring result = NULL;
    if (bytes > 2) {
        std::vector<unsigned short> value((bytes + 1) / 2, 0);
        FPDFAnnot_GetStringValue(annot, "Contents", value.data(), bytes);
        result = env->NewString(reinterpret_cast<const jchar*>(value.data()), bytes / 2 - 1);
    }
    FPDFPage_CloseAnnot(annot);
    return result;
}

/** Changes the /Contents text of annotation [index]. */
JNI_FUNC(jboolean, PdfiumCore, nativeSetAnnotContents)(JNI_ARGS, jlong pagePtr, jint index, jstring contents) {
    auto page = reinterpret_cast<FPDF_PAGE>(pagePtr);
    if (!page || !contents || index < 0 || index >= FPDFPage_GetAnnotCount(page)) return JNI_FALSE;
    auto annot = FPDFPage_GetAnnot(page, index);
    if (!annot) return JNI_FALSE;
    auto text = annotName(env, contents);
    bool ok = FPDFAnnot_SetStringValue(annot, "Contents", text.data());
    FPDFPage_CloseAnnot(annot);
    return ok ? JNI_TRUE : JNI_FALSE;
}

/**
 * A shape drawn on the page: kind 0 rectangle, 1 ellipse, 2 line, 3 arrow. [coords] is the
 * rectangle (left, top, right, bottom) or the line (x1, y1, x2, y2) in page points. The fill
 * (fillA > 0) applies to rectangles and ellipses.
 */
JNI_FUNC(jboolean, PdfiumCore, nativeAddShapeAnnot)(JNI_ARGS, jlong docPtr, jlong pagePtr, jint kind, jfloatArray coords,
        jint r, jint g, jint b, jint a, jfloat width, jint fr, jint fg, jint fb, jint fa, jstring name) {
    auto doc = reinterpret_cast<DocumentFile*>(docPtr);
    auto page = reinterpret_cast<FPDF_PAGE>(pagePtr);
    if (!doc || !page || !name || !coords || env->GetArrayLength(coords) != 4 || !(width > 0)) return JNI_FALSE;
    jfloat c[4];
    env->GetFloatArrayRegion(coords, 0, 4, c);
    for (float v : c) if (!std::isfinite(v)) return JNI_FALSE;
    std::vector<FPDF_PAGEOBJECT> objects;
    auto stroke = [&](FPDF_PAGEOBJECT path, bool fill) {
        FPDFPageObj_SetStrokeColor(path, r, g, b, a);
        FPDFPageObj_SetStrokeWidth(path, width);
        FPDFPageObj_SetLineCap(path, FPDF_LINECAP_ROUND);
        FPDFPageObj_SetLineJoin(path, FPDF_LINEJOIN_ROUND);
        if (fill) FPDFPageObj_SetFillColor(path, fr, fg, fb, fa);
        FPDFPath_SetDrawMode(path, fill ? FPDF_FILLMODE_WINDING : FPDF_FILLMODE_NONE, true);
        objects.push_back(path);
    };
    FS_RECTF bounds;
    float pad = width / 2 + 1;
    if (kind == 0 || kind == 1) {
        float l = std::min(c[0], c[2]), rr = std::max(c[0], c[2]), t = std::max(c[1], c[3]), bo = std::min(c[1], c[3]);
        if (rr - l < 1 || t - bo < 1) return JNI_FALSE;
        FPDF_PAGEOBJECT path;
        if (kind == 0) {
            path = FPDFPageObj_CreateNewRect(l, bo, rr - l, t - bo);
        } else {
            // four Bezier quarters
            float cx = (l + rr) / 2, cy = (t + bo) / 2, rx = (rr - l) / 2, ry = (t - bo) / 2, k = 0.5523f;
            path = FPDFPageObj_CreateNewPath(cx + rx, cy);
            FPDFPath_BezierTo(path, cx + rx, cy + ry * k, cx + rx * k, cy + ry, cx, cy + ry);
            FPDFPath_BezierTo(path, cx - rx * k, cy + ry, cx - rx, cy + ry * k, cx - rx, cy);
            FPDFPath_BezierTo(path, cx - rx, cy - ry * k, cx - rx * k, cy - ry, cx, cy - ry);
            FPDFPath_BezierTo(path, cx + rx * k, cy - ry, cx + rx, cy - ry * k, cx + rx, cy);
            FPDFPath_Close(path);
        }
        if (!path) return JNI_FALSE;
        stroke(path, fa > 0);
        bounds = {l - pad, t + pad, rr + pad, bo - pad};
    } else {
        float dx = c[2] - c[0], dy = c[3] - c[1];
        float len = sqrtf(dx * dx + dy * dy);
        if (len < 1) return JNI_FALSE;
        FPDF_PAGEOBJECT line = FPDFPageObj_CreateNewPath(c[0], c[1]);
        if (!line) return JNI_FALSE;
        FPDFPath_LineTo(line, c[2], c[3]);
        stroke(line, false);
        bounds = {std::min(c[0], c[2]) - pad, std::max(c[1], c[3]) + pad, std::max(c[0], c[2]) + pad, std::min(c[1], c[3]) - pad};
        if (kind == 3) {
            // a filled head at the end, its size following the line width
            float head = std::max(8.f, width * 4), ux = dx / len, uy = dy / len;
            float bx = c[2] - ux * head, by = c[3] - uy * head, px = -uy * head * 0.5f, py = ux * head * 0.5f;
            FPDF_PAGEOBJECT tip = FPDFPageObj_CreateNewPath(c[2], c[3]);
            FPDFPath_LineTo(tip, bx + px, by + py);
            FPDFPath_LineTo(tip, bx - px, by - py);
            FPDFPath_Close(tip);
            FPDFPageObj_SetStrokeColor(tip, r, g, b, a);
            FPDFPageObj_SetStrokeWidth(tip, width);
            FPDFPageObj_SetLineJoin(tip, FPDF_LINEJOIN_ROUND);
            FPDFPageObj_SetFillColor(tip, r, g, b, a);
            FPDFPath_SetDrawMode(tip, FPDF_FILLMODE_WINDING, true);
            objects.push_back(tip);
            for (float v : {bx + px, bx - px}) { bounds.left = std::min(bounds.left, v - pad); bounds.right = std::max(bounds.right, v + pad); }
            for (float v : {by + py, by - py}) { bounds.bottom = std::min(bounds.bottom, v - pad); bounds.top = std::max(bounds.top, v + pad); }
        }
    }
    auto annot = FPDFPage_CreateAnnot(page, FPDF_ANNOT_STAMP);
    if (!annot) { for (auto obj : objects) FPDFPageObj_Destroy(obj); return JNI_FALSE; }
    return finishObjectAnnot(env, page, annot, bounds, name, NULL, objects) ? JNI_TRUE : JNI_FALSE;
}

// ---- interactive forms --------------------------------------------------------------------

static void formNoop(FPDF_FORMFILLINFO*) {}
static void formInvalidate(FPDF_FORMFILLINFO*, FPDF_PAGE, double, double, double, double) {}
static void formOutputRect(FPDF_FORMFILLINFO*, FPDF_PAGE, double, double, double, double) {}
static void formSetCursor(FPDF_FORMFILLINFO*, int) {}
static int formSetTimer(FPDF_FORMFILLINFO*, int, TimerCallback) { return 0; }
static void formKillTimer(FPDF_FORMFILLINFO*, int) {}
static FPDF_SYSTEMTIME formLocalTime(FPDF_FORMFILLINFO*) { FPDF_SYSTEMTIME t = {}; t.wYear = 2000; t.wMonth = 1; t.wDay = 1; return t; }
static FPDF_PAGE formGetPage(FPDF_FORMFILLINFO*, FPDF_DOCUMENT, int) { return NULL; }
static FPDF_PAGE formCurrentPage(FPDF_FORMFILLINFO*, FPDF_DOCUMENT) { return NULL; }
static int formRotation(FPDF_FORMFILLINFO*, FPDF_PAGE) { return 0; }
static void formNamedAction(FPDF_FORMFILLINFO*, FPDF_BYTESTRING) {}
static void formFieldFocus(FPDF_FORMFILLINFO*, FPDF_WIDESTRING, FPDF_DWORD, FPDF_BOOL) {}
static void formUri(FPDF_FORMFILLINFO*, FPDF_BYTESTRING) {}
static void formGoTo(FPDF_FORMFILLINFO*, int, int, float*, int) {}

/** Sets up the interactive form of the document; false when it has none. Pages loaded later draw their fields. */
JNI_FUNC(jboolean, PdfiumCore, nativeInitForms)(JNI_ARGS, jlong docPtr) {
    auto doc = reinterpret_cast<DocumentFile*>(docPtr);
    if (!doc || !doc->pdfDocument) return JNI_FALSE;
    if (doc->form) return JNI_TRUE;
    if (FPDF_GetFormType(doc->pdfDocument) != FORMTYPE_ACRO_FORM) return JNI_FALSE;
    auto info = new FPDF_FORMFILLINFO();
    memset(info, 0, sizeof(FPDF_FORMFILLINFO));
    info->version = 1;
    info->FFI_Invalidate = formInvalidate;
    info->FFI_OutputSelectedRect = formOutputRect;
    info->FFI_SetCursor = formSetCursor;
    info->FFI_SetTimer = formSetTimer;
    info->FFI_KillTimer = formKillTimer;
    info->FFI_GetLocalTime = formLocalTime;
    info->FFI_OnChange = formNoop;
    info->FFI_GetPage = formGetPage;
    info->FFI_GetCurrentPage = formCurrentPage;
    info->FFI_GetRotation = formRotation;
    info->FFI_ExecuteNamedAction = formNamedAction;
    info->FFI_SetTextFieldFocus = formFieldFocus;
    info->FFI_DoURIAction = formUri;
    info->FFI_DoGoToAction = formGoTo;
    doc->form = FPDFDOC_InitFormFillEnvironment(doc->pdfDocument, info);
    if (!doc->form) { delete info; return JNI_FALSE; }
    doc->formInfo = info;
    return JNI_TRUE;
}



/**
 * The form fields of a loaded page, flattened into strings: per field "index type flags checked
 * left top right bottom optionCount", its name, its value, then its options, each one string
 * (UTF-16 as is: any character). Types are the FPDF_FORMFIELD_* values. Null without a form.
 */
JNI_FUNC(jobjectArray, PdfiumCore, nativeGetFormFields)(JNI_ARGS, jlong pagePtr) {
    auto page = reinterpret_cast<FPDF_PAGE>(pagePtr);
    auto found = sPageForms.find(page);
    if (!page || found == sPageForms.end()) return NULL;
    FPDF_FORMHANDLE form = found->second;
    std::vector<std::vector<unsigned short>> parts;
    auto ascii = [](const char* text) {
        std::vector<unsigned short> v;
        for (const char* c = text; *c; ++c) v.push_back((unsigned short) *c);
        return v;
    };
    auto utf16 = [&](FPDF_ANNOTATION annot, int kind, int option) {
        auto get = [&](unsigned short* b, unsigned long n) -> unsigned long {
            if (kind == 0) return FPDFAnnot_GetFormFieldName(form, annot, b, n);
            if (kind == 1) return FPDFAnnot_GetFormFieldValue(form, annot, b, n);
            return FPDFAnnot_GetOptionLabel(form, annot, option, b, n);
        };
        unsigned long bytes = get(NULL, 0);
        std::vector<unsigned short> v(std::max(1ul, (bytes + 1) / 2), 0);
        if (bytes) get(v.data(), bytes);
        v.resize(bytes >= 2 ? bytes / 2 - 1 : 0);
        return v;
    };
    int count = FPDFPage_GetAnnotCount(page);
    for (int i = 0; i < count; ++i) {
        auto annot = FPDFPage_GetAnnot(page, i);
        if (!annot) continue;
        if (FPDFAnnot_GetSubtype(annot) == FPDF_ANNOT_WIDGET) {
            int type = FPDFAnnot_GetFormFieldType(form, annot);
            FS_RECTF rect;
            if (type > 0 && FPDFAnnot_GetRect(annot, &rect)) {
                int options = std::max(0, FPDFAnnot_GetOptionCount(form, annot));
                char head[200];
                snprintf(head, sizeof(head), "%d %d %d %d %.9g %.9g %.9g %.9g %d", i, type,
                         FPDFAnnot_GetFormFieldFlags(form, annot), FPDFAnnot_IsChecked(form, annot) ? 1 : 0,
                         rect.left, rect.top, rect.right, rect.bottom, options);
                parts.push_back(ascii(head));
                parts.push_back(utf16(annot, 0, 0));
                parts.push_back(utf16(annot, 1, 0));
                for (int o = 0; o < options; ++o) parts.push_back(utf16(annot, 2, o));
            }
        }
        FPDFPage_CloseAnnot(annot);
    }
    auto cls = env->FindClass("java/lang/String");
    auto result = env->NewObjectArray(parts.size(), cls, NULL);
    for (size_t i = 0; i < parts.size(); ++i) {
        auto value = env->NewString(reinterpret_cast<const jchar*>(parts[i].data()), parts[i].size());
        env->SetObjectArrayElement(result, i, value);
        env->DeleteLocalRef(value);
    }
    return result;
}

/** Types [text] into text field [annotIndex], replacing what it held. */
JNI_FUNC(jboolean, PdfiumCore, nativeSetFormText)(JNI_ARGS, jlong pagePtr, jint annotIndex, jstring text) {
    auto page = reinterpret_cast<FPDF_PAGE>(pagePtr);
    auto found = sPageForms.find(page);
    if (!page || !text || found == sPageForms.end()) return JNI_FALSE;
    FPDF_FORMHANDLE form = found->second;
    auto annot = FPDFPage_GetAnnot(page, annotIndex);
    if (!annot) return JNI_FALSE;
    bool ok = FORM_SetFocusedAnnot(form, annot);
    if (ok) {
        auto value = annotName(env, text);
        FORM_SelectAllText(form, page);
        FORM_ReplaceSelection(form, page, value.data());
    }
    FORM_ForceToKillFocus(form);
    FPDFPage_CloseAnnot(annot);
    return ok ? JNI_TRUE : JNI_FALSE;
}

/** A tap on field [annotIndex] at its center: toggles a check box, picks a radio button. */
JNI_FUNC(jboolean, PdfiumCore, nativeClickFormField)(JNI_ARGS, jlong pagePtr, jint annotIndex) {
    auto page = reinterpret_cast<FPDF_PAGE>(pagePtr);
    auto found = sPageForms.find(page);
    if (!page || found == sPageForms.end()) return JNI_FALSE;
    FPDF_FORMHANDLE form = found->second;
    auto annot = FPDFPage_GetAnnot(page, annotIndex);
    if (!annot) return JNI_FALSE;
    FS_RECTF rect;
    bool ok = FPDFAnnot_GetRect(annot, &rect);
    FPDFPage_CloseAnnot(annot);
    if (!ok) return JNI_FALSE;
    double x = (rect.left + rect.right) / 2, y = (rect.top + rect.bottom) / 2;
    FORM_OnMouseMove(form, page, 0, x, y);
    ok = FORM_OnLButtonDown(form, page, 0, x, y);
    ok = FORM_OnLButtonUp(form, page, 0, x, y) || ok;
    FORM_ForceToKillFocus(form);
    return ok ? JNI_TRUE : JNI_FALSE;
}

/** Picks option [option] of combo box or list [annotIndex]. */
JNI_FUNC(jboolean, PdfiumCore, nativeSetFormChoice)(JNI_ARGS, jlong pagePtr, jint annotIndex, jint option) {
    auto page = reinterpret_cast<FPDF_PAGE>(pagePtr);
    auto found = sPageForms.find(page);
    if (!page || found == sPageForms.end()) return JNI_FALSE;
    FPDF_FORMHANDLE form = found->second;
    auto annot = FPDFPage_GetAnnot(page, annotIndex);
    if (!annot) return JNI_FALSE;
    bool ok = FORM_SetFocusedAnnot(form, annot) && FORM_SetIndexSelected(form, page, option, true);
    FORM_ForceToKillFocus(form);
    FPDFPage_CloseAnnot(annot);
    return ok ? JNI_TRUE : JNI_FALSE;
}

}//extern C
