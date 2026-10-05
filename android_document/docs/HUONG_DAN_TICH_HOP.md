# DocSDK — Hướng dẫn tích hợp (Android)

DocSDK giúp app Android **xem** tài liệu PDF, Word, Excel, PowerPoint, TXT và **xử lý PDF**: chuyển PDF sang Word, nén, gộp, tách, đặt/gỡ mật khẩu, nhận dạng chữ (OCR), ghép ảnh thành PDF, vẽ trang thành ảnh, tạo tài liệu Office trống.

SDK được phát hành dưới dạng thư viện đã biên dịch (AAR). Bạn không cần và không nhận mã nguồn.

| Bạn muốn | Dùng | Công sức |
|---|---|---|
| Mở một tài liệu toàn màn hình | `DocumentViewer.open(context, uri)` | 1 dòng |
| Hiện tài liệu trong màn hình của app | `DocumentView` | ~15 dòng |
| Cho người dùng sửa Word, Excel, PowerPoint | `DocumentViewer.Options(editFeatures = …)` hoặc `DocumentView.startEditing()` | 1–20 dòng |
| Xử lý PDF không cần giao diện | `DocumentTools` | vài dòng cho mỗi thao tác |

---

## Mục lục

1. [Yêu cầu](#1-yêu-cầu)
2. [Thêm SDK vào project](#2-thêm-sdk-vào-project)
3. [Bắt đầu nhanh: màn xem có sẵn](#3-bắt-đầu-nhanh-màn-xem-có-sẵn)
4. [Nhúng tài liệu vào màn hình của app: `DocumentView`](#4-nhúng-tài-liệu-vào-màn-hình-của-app-documentview)
5. [Xử lý tài liệu: `DocumentTools`](#5-xử-lý-tài-liệu-documenttools)
6. [Xử lý lỗi: `DocumentException`](#6-xử-lý-lỗi-documentexception)
7. [Định dạng hỗ trợ: `DocumentType`](#7-định-dạng-hỗ-trợ-documenttype)
8. [Tuỳ biến chữ và ngôn ngữ](#8-tuỳ-biến-chữ-và-ngôn-ngữ)
9. [R8 / ProGuard, kích thước app](#9-r8--proguard-kích-thước-app)
10. [Xử lý sự cố](#10-xử-lý-sự-cố)
11. [Giới hạn đã biết](#11-giới-hạn-đã-biết)
12. [Giấy phép bên thứ ba](#12-giấy-phép-bên-thứ-ba)
13. [Tra cứu API](#13-tra-cứu-api)

---

## 1. Yêu cầu

| Thành phần | Tối thiểu |
|---|---|
| `minSdk` của app | **26** (Android 8.0) |
| `compileSdk` của app | **36** |
| Android Gradle Plugin | **8.9.1** |
| Kotlin (nếu app viết bằng Kotlin) | **2.3.20** |
| Java | **17** (`compileOptions` của app) |
| Kiến trúc CPU | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` |

App viết hoàn toàn bằng Java vẫn dùng được SDK. Xem các ví dụ Java ở mục 3, 4 và 5.

SDK **không khai báo quyền nào**. SDK đọc tài liệu qua `Uri` do người dùng chọn (Storage Access Framework) hoặc qua file trong thư mục của app. Vì vậy app không cần xin quyền bộ nhớ chỉ để dùng SDK.

---

## 2. Thêm SDK vào project

SDK có toạ độ Maven là:

```
com.editor:docsdk:1.1.0
```

### 2.1. Khai báo kho Maven

Trong `settings.gradle.kts`, thêm kho chứa SDK vào `dependencyResolutionManagement`. Bạn được cấp **một** trong hai loại kho dưới đây.

**a) Kho dạng thư mục (thư mục `sdk-repo` được gửi kèm):** chép thư mục vào project hoặc để ở một đường dẫn cố định.

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven(url = uri("sdk-repo")) // đường dẫn tới thư mục sdk-repo, tính từ thư mục gốc project
    }
}
```

**b) Kho riêng trên GitHub Packages:** cần tài khoản được cấp quyền đọc và một Personal Access Token có quyền `read:packages`.

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/<owner>/<repo>") // địa chỉ được cung cấp
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
```

Để token trong `~/.gradle/gradle.properties` (không commit vào git):

```properties
gpr.user=ten-tai-khoan-github
gpr.key=ghp_xxxxxxxxxxxxxxxx
```

### 2.2. Thêm dependency

Trong `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.editor:docsdk:1.1.0")
}
```

Đồng bộ Gradle (Sync) là xong. Gradle tự tải các thư viện SDK cần: AndroidX, Kotlin coroutines, ML Kit Text Recognition.

Groovy (`build.gradle`):

```groovy
implementation 'com.editor:docsdk:1.1.0'
```

### 2.3. Nếu chỉ có file `.aar`

Nên dùng kho Maven, vì Gradle sẽ tự kéo các thư viện phụ thuộc. Nếu buộc phải dùng file `docsdk-1.1.0.aar`, hãy chép nó vào `app/libs/` và khai báo thêm các thư viện nó cần:

```kotlin
dependencies {
    implementation(files("libs/docsdk-1.1.0.aar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
}
```

Danh sách này lấy từ file `docsdk-1.1.0.pom`. Khi nâng phiên bản SDK, hãy xem lại file POM mới.

---

## 3. Bắt đầu nhanh: màn xem có sẵn

Một dòng code mở tài liệu toàn màn hình. Màn này có thanh tiêu đề, nút quay lại, số trang, hộp nhập mật khẩu khi tài liệu có mật khẩu, và thông báo lỗi.

**Kotlin**

```kotlin
import com.editor.docsdk.DocumentViewer

// uri từ trình chọn file, từ Intent của app khác, hoặc Uri.fromFile(file)
DocumentViewer.open(context, uri)

// hoặc với tiêu đề và mật khẩu biết trước
DocumentViewer.open(context, file, DocumentViewer.Options(title = "Hợp đồng", password = "1234"))
```

**Java**

```java
DocumentViewer.open(context, uri);
DocumentViewer.open(context, file, new DocumentViewer.Options("Hợp đồng", null));
```

Ví dụ đầy đủ: cho người dùng chọn file rồi mở.

```kotlin
class MainActivity : AppCompatActivity() {
    private val pickDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) DocumentViewer.open(this, uri)
    }

    fun onOpenClicked() {
        pickDocument.launch(arrayOf(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/plain",
        ))
    }
}
```

Nếu cần tự khởi chạy màn xem (thêm flag, dùng trong `PendingIntent`, trong thông báo...), lấy `Intent` bằng:

```kotlin
val intent = DocumentViewer.intent(context, uri, DocumentViewer.Options(title = "Báo cáo"))
```

**Mở tài liệu mà app khác chia sẻ.** Nếu app của bạn nhận `ACTION_VIEW` hoặc `ACTION_SEND`, chỉ cần chuyển `Uri` nhận được cho SDK:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val uri = intent.data ?: intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    if (uri != null) {
        DocumentViewer.open(this, uri)
        finish()
    }
}
```

**Cho phép sửa.** Truyền các tính năng được phép qua `editFeatures`. Với tài liệu `.docx`, `.xlsx`, `.pptx`, thanh tiêu đề sẽ có nút **Sửa**. Để trống (mặc định) thì màn này chỉ xem:

```kotlin
DocumentViewer.open(context, uri, DocumentViewer.Options(editFeatures = EditFeature.all()))

// chỉ cho sửa chữ và định dạng
DocumentViewer.open(context, uri, DocumentViewer.Options(
    editFeatures = setOf(EditFeature.TEXT, EditFeature.FORMAT, EditFeature.UNDO_REDO)))
```

Khi người dùng quay lại mà còn thay đổi chưa lưu, màn này hỏi có lưu không. Xem mục 4.4 để biết các tính năng.

> Với `content://`, SDK chép tài liệu vào bộ nhớ đệm của app (`cacheDir/docsdk`), vì bộ máy đọc cần một file thật. Bản chép cũ hơn một ngày tự bị xoá.

---

## 4. Nhúng tài liệu vào màn hình của app: `DocumentView`

`DocumentView` là một `View` (kế thừa `FrameLayout`). Nó tự chọn cách hiển thị phù hợp với loại tài liệu, hỗ trợ cuộn và phóng to/thu nhỏ bằng hai ngón.

### 4.1. Đặt vào layout

XML:

```xml
<com.editor.docsdk.DocumentView
    android:id="@+id/documentView"
    android:layout_width="match_parent"
    android:layout_height="0dp"
    android:layout_weight="1" />
```

Hoặc tạo bằng code: `val documentView = DocumentView(this)`.

> **Quan trọng:** `DocumentView` phải nằm trong một **Activity**. Context của nó phải là Activity, không được là `applicationContext`. Nếu đặt trong Fragment, hãy dùng `requireActivity()` hoặc inflate từ layout của Fragment như bình thường.

### 4.2. Mở tài liệu, nghe sự kiện, giải phóng

```kotlin
class ReaderActivity : AppCompatActivity() {
    private lateinit var documentView: DocumentView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)
        documentView = findViewById(R.id.documentView)

        documentView.listener = object : DocumentView.Listener {
            override fun onLoaded(pageCount: Int) {
                pageLabel.text = "1/$pageCount"
            }

            override fun onPageChanged(page: Int, pageCount: Int) {
                pageLabel.text = "${page + 1}/$pageCount"
            }

            override fun onError(error: DocumentException) {
                when (error.reason) {
                    DocumentException.Reason.PASSWORD_REQUIRED,
                    DocumentException.Reason.PASSWORD_INCORRECT -> askPassword { pw -> documentView.open(uri, pw) }
                    else -> showMessage("Không mở được tài liệu (${error.reason})")
                }
            }
        }
        documentView.open(uri)           // hoặc documentView.open(file), open(uri, password)
    }

    override fun onDestroy() {
        documentView.close()             // giải phóng bộ nhớ của tài liệu
        super.onDestroy()
    }
}
```

**Java**

```java
documentView.setListener(new DocumentView.Listener() {
    @Override public void onLoaded(int pageCount) { /* ... */ }
    @Override public void onPageChanged(int page, int pageCount) { /* ... */ }
    @Override public void onError(DocumentException error) { /* ... */ }
});
documentView.open(uri);
// onDestroy:
documentView.close();
```

`DocumentView.Listener` có sẵn phần thân rỗng cho cả ba hàm. Trong Kotlin chỉ cần viết hàm bạn dùng. Trong Java phải viết đủ cả ba.

### 4.3. Các thuộc tính và hàm

| Thành viên | Ý nghĩa |
|---|---|
| `open(uri: Uri, password: String? = null)` | Mở `content://` hoặc `file://`. |
| `open(file: File, password: String? = null)` | Mở file. Loại tài liệu xác định theo phần mở rộng. |
| `goToPage(page: Int)` | Tới trang `page`, đếm từ 0. |
| `close()` | Đóng tài liệu và giải phóng bộ nhớ. Sau đó vẫn `open` tài liệu khác được. |
| `listener: Listener?` | Nhận `onLoaded`, `onPageChanged`, `onError`. |
| `documentType: DocumentType?` | Loại tài liệu vừa mở. |
| `pageCount: Int` | Số trang: 0 khi chưa mở xong. Với Word dài, số này tăng dần trong lúc dàn trang. |
| `currentPage: Int` | Trang đang hiển thị, đếm từ 0. |

Lưu ý:

- Mọi hàm của `DocumentView` gọi trên **luồng chính**, và listener cũng được gọi trên luồng chính.
- Gọi `open` khi đang có tài liệu thì tài liệu cũ tự đóng trước.
- Với Excel, mỗi sheet tính là một trang. Với PowerPoint, mỗi slide là một trang.
- Tài liệu **không tự đóng** khi view bị gỡ khỏi cửa sổ, để dùng được trong `ViewPager` hay Fragment. Hãy gọi `close()` khi màn hình kết thúc.

### 4.4. Sửa tài liệu

`DocumentView` sửa được tài liệu Word (`.docx`), Excel (`.xlsx`, `.xlsm`) và PowerPoint (`.pptx`) ngay trên màn hình. Có hai cách:

- **Thanh sửa có sẵn** (mặc định): một thanh ở đáy view, gồm hàng trên cùng (Hoàn tác, Làm lại, dòng trạng thái, Lưu), các tab (Trang đầu, Chèn, Đoạn, Bảng…) và hàng icon của tab đang chọn.
- **Thanh của app**: SDK không vẽ thanh nào. App tự đặt nút ở đâu tuỳ ý, chọn lệnh nào (`EditAction`) và gọi `DocumentEditor.run(...)`.

Cả hai cách đều giữ các thao tác chạm trên tài liệu: gõ chữ trong Word, chọn ô, kéo ảnh, chọn hình… `EditFeature` quyết định người dùng được làm gì.

#### a) Thanh sửa có sẵn

```kotlin
documentView.listener = object : DocumentView.Listener {
    override fun onLoaded(pageCount: Int) {
        editButton.isVisible = documentView.canEdit
    }
    override fun onEditingChanged(editing: Boolean) { /* đổi nút Sửa ↔ Xong */ }
    override fun onSaved(file: File) { /* đã ghi xong */ }
}

editButton.setOnClickListener {
    if (!documentView.isEditing) {
        documentView.startEditing(setOf(EditFeature.TEXT, EditFeature.FORMAT, EditFeature.PICTURES, EditFeature.UNDO_REDO))
    } else if (documentView.hasUnsavedChanges()) {
        askSaveOrDiscard(
            onSave = { if (documentView.save()) documentView.stopEditing() },
            onDiscard = { documentView.stopEditing() },
        )
    } else {
        documentView.stopEditing()
    }
}
```

**Java**

```java
documentView.startEditing(EnumSet.of(EditFeature.TEXT, EditFeature.FORMAT));
documentView.startEditing(); // mọi tính năng
```

#### b) Thanh của app: `DocumentEditor` và `EditAction`

Truyền `showToolbar = false`, rồi gắn lệnh vào nút của app:

```kotlin
val editor = documentView.startEditing(EditFeature.all(), showToolbar = false) ?: return

// chỉ hiện nút mà tài liệu này có (Word, Excel, PowerPoint có lệnh khác nhau)
boldButton.isVisible = editor.isAvailable(EditAction.BOLD)
boldButton.setOnClickListener { editor.run(EditAction.BOLD) }

// lệnh cần giá trị: SDK tự hỏi người dùng, hoặc app truyền giá trị vào
colorButton.setOnClickListener { editor.run(EditAction.TEXT_COLOR) }          // hộp chọn màu
redButton.setOnClickListener { editor.run(EditAction.TEXT_COLOR, "C00000") }  // không hỏi
tableButton.setOnClickListener { editor.run(EditAction.INSERT_TABLE, TableSize(3, 4)) }

// cập nhật trạng thái nút (đậm đang bật...) và dòng gợi ý
editor.listener = object : DocumentEditor.Listener {
    override fun onStateChanged() { boldButton.isSelected = editor.isActive(EditAction.BOLD) }
    override fun onStatusChanged(status: String) { hint.text = status }
}
```

Muốn dựng cả thanh từ danh sách lệnh, dùng `editor.actions`: các lệnh của tài liệu này mà `EditFeature` cho phép, theo thứ tự của thanh có sẵn. Mỗi `EditAction` có sẵn `icon` (drawable 24dp, màu trắng, hãy tô màu) và `label` (tên, có tiếng Anh và tiếng Việt), app dùng tuỳ ý:

```kotlin
for (action in editor.actions) {
    toolbar.addView(ImageButton(this).apply {
        setImageResource(action.icon)
        imageTintList = ColorStateList.valueOf(iconColor)
        contentDescription = getString(action.label)
        setOnClickListener { editor.run(action) }
    })
}
```

| `DocumentEditor` | Ý nghĩa |
|---|---|
| `actions: List<EditAction>` | Các lệnh dùng được với tài liệu này. |
| `isAvailable(action)` | Lệnh có dùng được không. |
| `run(action)` | Chạy lệnh. Nếu cần giá trị, SDK hỏi người dùng. Trả về `false` nếu lệnh không dùng được. |
| `run(action, value)` | Chạy lệnh với giá trị app đưa, không hỏi. Kiểu giá trị ghi ở từng `EditAction`, xem bảng dưới. |
| `isActive(action)` | Lệnh đang bật ở chỗ con trỏ hoặc vùng chọn (đậm, nghiêng, gạch chân, gạch giữa, chỉ số trên/dưới, đầu dòng, đánh số, xuống dòng trong ô). |
| `status: String` | Câu gợi ý: đang chọn gì, làm gì tiếp. |
| `listener` | `onStateChanged()` khi vùng chọn đổi, `onStatusChanged(status)`. |
| `dialogs: EditDialogs?` | Dialog của app thay cho dialog của SDK, xem c). |
| `hasUnsavedChanges()`, `save()` | Như `DocumentView`. |
| `documentType` | `WORD`, `EXCEL` hoặc `POWERPOINT`. |

**Giá trị của `run(action, value)`:**

| Lệnh | Giá trị |
|---|---|
| `TEXT_COLOR` | Màu `"RRGGBB"` |
| `HIGHLIGHT` (Word), `FILL_COLOR` (Excel) | Màu `"RRGGBB"`, hoặc `null` để bỏ màu |
| `FONT` (Word) | Tên font |
| `FONT_SIZE` | Cỡ chữ theo point (số) |
| `INSERT_TEXT`, `REPLACE_TEXT` (Word), `SET_TEXT`, `ADD_TEXT_BOX` (PowerPoint), `ADD_SHEET` (Excel) | Chữ |
| `INSERT_PICTURE` | `Uri` hoặc `File` của ảnh |
| `INSERT_TABLE` (Word) | `TableSize(rows, columns)` |
| `CELL_VALUE` (Excel) | Giá trị hoặc `"=công thức"` |
| `GO_TO_CELL` (Excel) | `"B3"` hoặc `"A1:C10"` |
| `NUMBER_FORMAT` (Excel) | Mã định dạng: `"General"`, `"0"`, `"0.00"`, `"#,##0"`, `"0%"`, `"d/m/yyyy"`, `"@"`… (một trong các mã của danh sách có sẵn) |
| `TEXT_ROTATION` (Excel) | Góc `0`, `45`, `90`, `135`, `180`, hoặc `255` (chữ xếp dọc) |
| `COLUMN_WIDTH` (Excel) | Số ký tự; `ROW_HEIGHT`: point |
| `MERGE_CELLS` (Excel) | `true`: gộp luôn, không hỏi khi có giá trị bị bỏ |

Các lệnh còn lại không cần giá trị. `PARAGRAPH`, `LINE_SPACING`, `FIND_REPLACE`, `CELL_ALIGNMENT`, `BORDERS`, `SHAPE_LIST`, `ANIMATIONS`, `TRANSITION` luôn mở dialog của SDK.

#### c) Dialog của app: `EditDialogs`

Khi một lệnh cần hỏi người dùng (màu, cỡ chữ, font, một lựa chọn, một đoạn chữ, một số, kích thước bảng, xác nhận, ảnh), SDK gửi một `EditRequest` cho `editor.dialogs`. Nếu app xử lý thì trả `true` rồi gọi `answer(...)` khi người dùng chọn xong, hoặc `cancel()`. Nếu trả `false`, SDK dùng dialog của nó. Cách này dùng được cả với thanh sửa có sẵn.

```kotlin
editor.dialogs = EditDialogs { request ->
    when (request) {
        is EditRequest.Color -> {
            MyColorSheet.show(supportFragmentManager, allowNone = request.allowsNone) { hex -> request.answer(hex) }
            true
        }
        is EditRequest.FontSize -> {
            MySizePicker.show(request.sizes) { pt -> request.answer(pt) }
            true
        }
        else -> false   // các câu hỏi khác: dialog của SDK
    }
}
```

| `EditRequest` | Có gì | Trả lời |
|---|---|---|
| `Color` | `title`, `allowsNone` | `answer("RRGGBB")`, `answer(null)` khi `allowsNone` |
| `FontSize` | `sizes` gợi ý | `answer(points)` |
| `Font` | `fonts`, `current` | `answer(name)` |
| `Choice` | `options`, `selected` | `answer(index)` |
| `Text` | `hint`, `initial` | `answer(text)` |
| `Number` | `initial` | `answer(value)` |
| `Table` | | `answer(rows, columns)` |
| `Confirm` | `message` | `answer()` để làm tiếp |
| `Picture` | | `answer(uri)` |

Mỗi request có `action` (lệnh đang hỏi, có thể `null` khi hỏi từ thao tác chạm) và `title`, và chỉ trả lời được một lần.

#### d) Các thành viên của `DocumentView` dùng khi sửa

| Thành viên | Ý nghĩa |
|---|---|
| `canEdit: Boolean` | Sửa được không: tài liệu đã mở xong, là `.docx`/`.xlsx`/`.xlsm`/`.pptx`, không có mật khẩu, và view nằm trong `AppCompatActivity`. |
| `startEditing(features = EditFeature.all(), showToolbar = true): DocumentEditor?` | Bắt đầu sửa với các tính năng cho phép, có hoặc không có thanh sẵn. Trả về `null` nếu không sửa được. |
| `editor: DocumentEditor?` | Bộ sửa khi đang sửa. |
| `isEditing: Boolean` | Đang sửa. |
| `hasUnsavedChanges(): Boolean` | Còn thay đổi chưa lưu. |
| `save(): Boolean` | Ghi thay đổi vào tài liệu. Trả về `false` nếu không ghi được (người dùng thấy lý do). |
| `stopEditing()` | Thôi sửa. **Thay đổi chưa lưu sẽ bị bỏ**, tài liệu hiện lại như trong file. |
| `Listener.onEditingChanged(editing)` | Vừa bắt đầu hoặc thôi sửa. |
| `Listener.onSaved(file)` | Đã ghi xong. |

**Tính năng (`EditFeature`).** Tính năng bị tắt thì các lệnh của nó không dùng được (`isAvailable` trả `false`, nút ẩn khỏi thanh có sẵn), và thao tác chạm tương ứng trên tài liệu cũng tắt. Lệnh **Lưu** luôn có. Tính năng không áp dụng cho một định dạng thì được bỏ qua.

| `EditFeature` | Word | Excel | PowerPoint |
|---|---|---|---|
| `TEXT` | Gõ, xoá, chèn/thay chữ | Nhập giá trị, công thức; xoá ô | Sửa chữ của hình |
| `FORMAT` | Đậm, nghiêng, gạch, màu, font, cỡ, tô màu | Font, màu, nền, căn lề, viền, định dạng số, xoay, xuống dòng | Đậm, nghiêng, gạch chân, màu, cỡ, căn lề |
| `PARAGRAPH` | Đầu dòng, đánh số, căn lề, thụt lề, giãn dòng | — | — |
| `CLIPBOARD` | Chép, cắt, dán | — | — |
| `FIND_REPLACE` | Tìm và thay | — | — |
| `PICTURES` | Chèn, di chuyển, đổi cỡ ảnh | Chèn, di chuyển, đổi cỡ, xoá ảnh | Chèn ảnh |
| `TABLES` | Chèn bảng, thêm/xoá hàng cột, kéo bảng, đổi cỡ cột hàng | — | — |
| `ROWS_COLUMNS` | — | Thêm/xoá hàng cột, đổi độ rộng, chiều cao | — |
| `MERGE_CELLS` | — | Gộp và tách ô | — |
| `SHEETS` | — | Thêm sheet | — |
| `SHAPES` | — | — | Thêm text box, hình; di chuyển, xoay, xoá, đổi lớp |
| `SLIDES` | — | — | Thêm, nhân bản, di chuyển, xoá slide |
| `ANIMATIONS` | — | — | Hiệu ứng, chuyển slide |
| `EXPORT` | — | — | Xuất slide ra PNG, PDF |
| `UNDO_REDO` | Hoàn tác, làm lại | như Word | như Word |
| `SAVE_COPY` | Lưu bản sao ra chỗ người dùng chọn | như Word | như Word |

**Lưu vào đâu.** Tài liệu mở bằng `File` được ghi đè lên chính file đó. Tài liệu mở bằng `content://` được ghi vào bản chép trong bộ nhớ đệm, rồi chép ngược lại `Uri`. Vì vậy app cần quyền **ghi** `Uri`. Ví dụ, chọn file bằng `ActivityResultContracts.OpenDocument()`, hoặc nhận `Uri` kèm `FLAG_GRANT_WRITE_URI_PERMISSION`. Nếu không ghi được, `onError` nhận `reason = STORAGE`.

Lưu ý:

- Activity chứa view phải là `AppCompatActivity`. SDK dùng nó để chọn ảnh và chỗ lưu bản sao.
- Gọi `close()` hay `open()` khi đang sửa thì việc sửa dừng và thay đổi chưa lưu bị bỏ. Hãy hỏi người dùng trước.
- Khi dùng thanh của app, hãy đặt thanh đó phía trên bàn phím nếu cần: SDK chỉ tự nâng thanh có sẵn của nó.
- Màu, cỡ chữ của thanh sửa có sẵn và của dialog SDK đổi được bằng code: xem `TUY_BIEN_GIAO_DIEN.md`.

---

### 4.5. Dùng trực tiếp `PDFView` (API nâng cao)

Từ `1.1.0`, app có thể dùng trực tiếp `com.reader.pdfviewer.PDFView` khi cần các API PDF
nâng cao như mục lục, metadata, liên kết, tìm kiếm, lựa chọn chữ hoặc chú thích. Với nhu cầu xem
tài liệu thông thường, vẫn nên dùng `DocumentView` để cùng một API mở được mọi định dạng.

```xml
<com.reader.pdfviewer.PDFView
    android:id="@+id/pdfView"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

Mục lục chỉ có sau khi tài liệu tải xong:

```kotlin
pdfView.fromUri(uri)
    .onLoad {
        val tableOfContents = pdfView.tableOfContents.orEmpty()
        tableOfContents.forEach { bookmark ->
            bookmark ?: return@forEach
            val title = bookmark.title
            val page = bookmark.pageIdx.toInt() // chỉ số trang bắt đầu từ 0
            val children = bookmark.children
        }
    }
    .load()

override fun onDestroy() {
    pdfView.recycle()
    super.onDestroy()
}
```

Các class public trong package `com.reader.pdfviewer` và các package con được giữ nguyên tên khi
publish để app tích hợp có thể gọi trực tiếp. Các thành phần không public vẫn là implementation
nội bộ và có thể thay đổi giữa các phiên bản.

## 5. Xử lý tài liệu: `DocumentTools`

`DocumentTools` xử lý tài liệu mà không cần giao diện. Tạo một lần rồi dùng lại:

```kotlin
val tools = DocumentTools(context)   // giữ applicationContext, không giữ Activity
```

Nguyên tắc chung:

- **Kotlin:** mỗi thao tác là một hàm `suspend`. Gọi trong coroutine, ví dụ `lifecycleScope.launch { ... }`. Công việc nặng tự chạy trên luồng nền.
- **Java:** dùng các hàm `...Async(..., callback)`. Kết quả trả về trên luồng chính, và hàm trả về một `DocumentTools.Task` để huỷ (`task.cancel()`).
- Kết quả luôn được ghi ra **file mới** tại `output` (thư mục tự được tạo). File đầu vào không bao giờ bị sửa.
- Thao tác lỗi hoặc bị huỷ **không để lại file kết quả** dở dang.
- Mọi lỗi đều là `DocumentException` (mục 6).
- Đầu vào là `File`. Nếu bạn có `Uri`, hãy chép ra file trước:

```kotlin
val input = File(context.cacheDir, "input.pdf")
context.contentResolver.openInputStream(uri)!!.use { i -> input.outputStream().use { i.copyTo(it) } }
```

### 5.1. PDF sang Word

```kotlin
lifecycleScope.launch {
    try {
        val pages = tools.pdfToWord(
            input = File(cacheDir, "input.pdf"),
            output = File(getExternalFilesDir(null), "ket-qua.docx"),
            password = null,
        ) { done, total -> runOnUiThread { progress.text = "$done/$total" } }
        DocumentViewer.open(this@MyActivity, File(getExternalFilesDir(null), "ket-qua.docx"))
    } catch (e: DocumentException) {
        // e.reason
    }
}
```

**Giữ được:** chữ theo đoạn, cỡ chữ, đậm, nghiêng, màu, font, căn lề, thụt lề, ảnh (kể cả ảnh nền trong suốt), hình minh hoạ (chèn thành ảnh), thứ tự đọc của trang 2 cột, mỗi trang PDF sang một trang Word. Trang scan được nhận dạng chữ (OCR).

**Chưa giữ:** bảng (thành các dòng chữ), header/footer (nằm trong nội dung), danh sách đánh số tự động (giữ chữ số nhưng không thành danh sách của Word).

`progress` được gọi trên **luồng nền**. Muốn cập nhật giao diện thì chuyển về luồng chính, như `runOnUiThread` ở ví dụ trên.

### 5.2. Nén PDF

```kotlin
val changed = tools.compressPdf(input, output, DocumentTools.Compression.MEDIUM)
```

| Mức | Kết quả |
|---|---|
| `LOW` | Giữ chất lượng tốt nhất, giảm ít (ảnh khoảng 200 dpi) |
| `MEDIUM` | Cân bằng, khuyên dùng (khoảng 144 dpi) |
| `HIGH` | File nhỏ nhất (khoảng 96 dpi) |

Giá trị trả về là số ảnh đã được thu nhỏ. Ảnh có nền trong suốt được giữ nguyên. PDF chỉ có chữ thường giảm rất ít.

### 5.3. Gộp và tách

```kotlin
tools.mergePdfs(listOf(a, b, c), output)            // mọi trang của a, rồi b, rồi c
tools.splitPdf(input, intArrayOf(0, 2, 4), output)  // trang 1, 3, 5 (đếm từ 0), theo đúng thứ tự đưa vào
```

### 5.4. Mật khẩu

```kotlin
// đặt mật khẩu mở file (AES-256); cho phép hoặc chặn in và sao chép chữ
tools.protectPdf(input, output, newPassword = "1234", allowPrint = true, allowCopy = false)

// gỡ mật khẩu
tools.unprotectPdf(input, password = "1234", output = output)
```

Mọi hàm đọc PDF đều nhận thêm tham số `password` cho file đang có mật khẩu. Thiếu mật khẩu thì lỗi có `reason = PASSWORD_REQUIRED`, sai mật khẩu thì `PASSWORD_INCORRECT`.

### 5.5. Nhận dạng chữ (OCR)

```kotlin
val pages = tools.recognizeText(input, output)
```

Thêm một lớp chữ vô hình vào các trang scan, để tìm kiếm, bôi đen và sao chép được. Nhận dạng chạy **trên máy** bằng ML Kit, không gửi dữ liệu đi, và hỗ trợ chữ Latin (có tiếng Việt). Trang đã có chữ thì bỏ qua.

### 5.6. Ảnh sang PDF

```kotlin
val pages = tools.imagesToPdf(listOf(photo1, photo2), output)  // mỗi ảnh một trang, theo thứ tự
```

Hỗ trợ JPEG, PNG, WebP. Ảnh lớn được thu về tối đa 2480 px để file không quá nặng.

### 5.7. Số trang, ảnh của một trang

```kotlin
val count = tools.pageCount(pdf)
val bitmap: Bitmap = tools.renderPage(pdf, page = 0, widthPx = 300)   // ảnh thu nhỏ trang đầu
```

### 5.8. Tạo tài liệu Office trống

```kotlin
val file = tools.createDocument(DocumentType.WORD, File(dir, "moi.docx"))
// DocumentType.EXCEL → .xlsx, DocumentType.POWERPOINT → .pptx
```

Phần mở rộng của `output` phải khớp với loại tài liệu. File đã tồn tại sẽ không bị ghi đè; khi đó hàm báo lỗi `STORAGE`.

### 5.9. Dùng từ Java

```java
DocumentTools tools = new DocumentTools(context);

DocumentTools.Task task = tools.pdfToWordAsync(input, output, null,
        (done, total) -> Log.d("DocSDK", done + "/" + total),
        new DocumentTools.Callback<Integer>() {
            @Override public void onSuccess(Integer pages) { /* luồng chính */ }
            @Override public void onError(DocumentException error) { /* luồng chính */ }
        });

// khi rời màn hình:
task.cancel();
```

| Kotlin (`suspend`) | Java (callback) |
|---|---|
| `pdfToWord` | `pdfToWordAsync` |
| `compressPdf` | `compressPdfAsync` |
| `mergePdfs` | `mergePdfsAsync` |
| `splitPdf` | `splitPdfAsync` |
| `protectPdf` | `protectPdfAsync` |
| `unprotectPdf` | `unprotectPdfAsync` |
| `recognizeText` | `recognizeTextAsync` |
| `imagesToPdf` | `imagesToPdfAsync` |
| `pageCount` | `pageCountAsync` |
| `renderPage` | `renderPageAsync` |
| `createDocument` | `createDocumentAsync` |

### 5.10. Huỷ

- **Kotlin:** huỷ coroutine đang chạy thao tác, ví dụ `job.cancel()`, hoặc để `lifecycleScope` tự huỷ khi màn hình đóng.
- **Java:** gọi `task.cancel()`.

Khi bị huỷ, callback không được gọi và không có file kết quả.

---

## 6. Xử lý lỗi: `DocumentException`

Mọi lỗi của SDK đều là `DocumentException`, kèm `reason` cho biết nên báo gì với người dùng:

| `reason` | Khi nào | Gợi ý xử lý |
|---|---|---|
| `PASSWORD_REQUIRED` | Tài liệu có mật khẩu, chưa đưa mật khẩu | Hỏi mật khẩu rồi gọi lại với `password` |
| `PASSWORD_INCORRECT` | Mật khẩu sai | Hỏi lại |
| `UNSUPPORTED` | Không phải loại SDK mở được, hoặc định dạng quá cũ | Báo "không hỗ trợ" |
| `DAMAGED` | File hỏng hoặc không đúng như phần mở rộng | Báo "file bị hỏng" |
| `NOT_FOUND` | Không tìm thấy hoặc không đọc được file / `Uri` | Kiểm tra đường dẫn, quyền đọc `Uri` |
| `OUT_OF_MEMORY` | Không đủ bộ nhớ | Xem mục 10 |
| `STORAGE` | Không ghi được file kết quả (đầy bộ nhớ, không có quyền, file đã tồn tại) | Chọn chỗ ghi khác |
| `UNKNOWN` | Lỗi khác | Xem `cause` để ghi log |

`error.cause` giữ lỗi gốc, dùng khi ghi log hoặc gửi báo cáo lỗi.

**Lỗi sau khi tài liệu đã mở.** Tài liệu dài được đọc tiếp ở luồng nền sau khi trang đầu đã hiện (ví dụ các slide sau của một file PowerPoint). Nếu phần đọc tiếp, dàn trang hoặc vẽ bị lỗi, `DocumentView` gọi `Listener.onError` **sau** `onLoaded`, một lần cho mỗi lần mở, thay cho hộp thoại lỗi của SDK. App tự báo cho người dùng và đóng màn hình nếu muốn.

**Hộp thoại lỗi mặc định.** Khi app dùng thẳng `OfficeReader` / `OfficeDocumentView` mà không gắn `onOpenFailure` (hoặc trả `false`), SDK hiện hộp thoại lỗi của nó rồi đóng `Activity` khi bấm OK. Hộp thoại này theo `DialogStyle` như các hộp thoại khác và dùng các chuỗi `docsdk_error_*`, `docsdk_password_*` (mục 8), nên đổi màu và đổi chữ được mà không cần tự dựng hộp thoại.

---

## 7. Định dạng hỗ trợ: `DocumentType`

| `DocumentType` | Phần mở rộng |
|---|---|
| `PDF` | `.pdf` |
| `WORD` | `.docx`, `.doc`, `.dotx`, `.dot`, `.docm` |
| `EXCEL` | `.xlsx`, `.xls`, `.xltx`, `.xlt`, `.xlsm` |
| `POWERPOINT` | `.pptx`, `.ppt`, `.potx`, `.pot`, `.ppsx`, `.pps`, `.pptm` |
| `TEXT` | `.txt` |

```kotlin
DocumentType.fromFileName("bao-cao.xlsx")        // EXCEL
DocumentType.fromMimeType("application/pdf")     // PDF
DocumentViewer.canOpen("anh.png")                // false
```

---

## 8. Tuỳ biến chữ và ngôn ngữ

Màn xem có sẵn có chữ tiếng Anh và tiếng Việt, và tự theo ngôn ngữ của máy. Muốn đổi chữ, khai báo lại chuỗi cùng tên trong `res/values/strings.xml` của app. Chuỗi của app sẽ được dùng thay chuỗi của SDK:

```xml
<string name="docsdk_password_required">Tài liệu được bảo vệ. Vui lòng nhập mật khẩu.</string>
<string name="docsdk_error_generic">Rất tiếc, không thể mở tài liệu này.</string>
```

Danh sách chuỗi có thể đổi:

| Tên | Mặc định (EN) |
|---|---|
| `docsdk_page_of` | `%1$d / %2$d` |
| `docsdk_back` | Back |
| `docsdk_open` | Open |
| `docsdk_password_title` | Password |
| `docsdk_password_hint` | Password |
| `docsdk_password_required` | This document is protected. Enter its password to open it. |
| `docsdk_password_incorrect` | The password is incorrect. Try again. |
| `docsdk_error_unsupported` | This type of document cannot be opened. |
| `docsdk_error_damaged` | This document is damaged and cannot be opened. |
| `docsdk_error_not_found` | This document cannot be found or read. |
| `docsdk_error_memory` | There is not enough memory to open this document. |
| `docsdk_error_generic` | This document could not be opened. |

Chữ của thanh sửa và hộp thoại của nó (khoảng 320 chuỗi, tên bắt đầu bằng `docsdk_edit_`, ví dụ `docsdk_edit_save`, `docsdk_edit_start`) đổi được cùng cách này. Danh sách đầy đủ nằm trong `res/values/docsdk_edit_strings.xml` của file AAR. SDK có sẵn tiếng Anh và tiếng Việt. Muốn thêm ngôn ngữ khác, hãy khai báo các chuỗi đó trong `res/values-<mã ngôn ngữ>/` của app.

Muốn đổi màu, cỡ chữ, nút của thanh sửa và hộp thoại, xem `TUY_BIEN_GIAO_DIEN.md` (`EditStyle`, `DialogStyle`). Muốn đổi bố cục màn xem, hãy tự làm màn hình bằng `DocumentView` (mục 4).

---

## 9. R8 / ProGuard, kích thước app

**R8/ProGuard:** không cần thêm luật nào. Luật cần thiết đi kèm trong AAR (consumer rules) và tự áp dụng khi app bật `isMinifyEnabled = true`.

**Kích thước:** phần lớn dung lượng SDK nằm ở thư viện native (bộ đọc PDF) của 4 kiến trúc CPU. Nếu phát hành bằng **Android App Bundle** (`.aab`) thì Google Play tự chỉ giao đúng kiến trúc của từng máy. Nếu phát hành APK, có thể tách theo kiến trúc:

```kotlin
android {
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }
}
```

---

## 10. Xử lý sự cố

**`Class '...' was compiled with an incompatible version of Kotlin`**
App đang dùng Kotlin cũ hơn 2.3.20. Nâng Kotlin của app lên 2.3.20 hoặc mới hơn. Với AGP 9 (Kotlin tích hợp sẵn), đặt phiên bản trong `build.gradle.kts` gốc:

```kotlin
plugins {
    id("com.android.application") version "9.1.1" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
}
```

**`Dependency ... requires libraries and applications that depend on it to compile against version 36 or later`**
Đặt `compileSdk = 36` (hoặc mới hơn) trong `app/build.gradle.kts`.

**`2 files found with path 'lib/arm64-v8a/libc++_shared.so'`**
App hoặc một thư viện khác cũng mang `libc++_shared.so`. Thêm vào `app/build.gradle.kts`:

```kotlin
android {
    packaging {
        jniLibs.pickFirsts += "**/libc++_shared.so"
    }
}
```

**`Duplicate class ...` với một thư viện AndroidX hay ML Kit**
App đang dùng phiên bản khác của cùng thư viện. Gradle thường tự chọn bản mới nhất. Nếu vẫn trùng, hãy loại bản bị trùng:

```kotlin
implementation("com.editor:docsdk:1.1.0") {
    exclude(group = "androidx.viewpager2", module = "viewpager2")
}
```

**`DocumentView` trắng, hoặc lỗi `Activity` khi tạo view**
Context của `DocumentView` phải là Activity (mục 4.1).

**App bị đứng (ANR) hoặc thiếu bộ nhớ với file rất lớn** (Excel hàng trăm nghìn dòng, PDF hàng nghìn trang)
Thêm `android:largeHeap="true"` vào thẻ `<application>` trong `AndroidManifest.xml` của app. Trên máy yếu, nên báo trước cho người dùng khi file quá lớn.

**Không mở được `content://` từ app khác sau một thời gian**
Quyền đọc `Uri` chỉ tồn tại trong phiên hiện tại. Muốn mở lại sau này, hãy gọi `contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)` khi chọn file bằng `OpenDocument`, hoặc tự chép file vào thư mục của app.

**Lỗi báo về có stack trace khó đọc** (tên class kiểu `com.editor.docsdk.internal.a1`)
Mã bên trong SDK đã được làm rối. Hãy gửi nguyên stack trace kèm số phiên bản SDK cho nhà cung cấp để được giải mã.

---

## 11. Giới hạn đã biết

- **Xem:** tài liệu `.doc`/`.xls`/`.ppt` rất cũ hoặc RTF có thể không mở được (`UNSUPPORTED`).
- **Sửa:** chỉ sửa được `.docx`, `.xlsx`, `.xlsm`, `.pptx` không có mật khẩu. `.doc`, `.xls`, `.ppt` và PDF chỉ xem. Một vài thông báo lỗi của bộ máy sửa (ví dụ tên sheet không hợp lệ, bảng chỉ còn một hàng) hiện luôn bằng tiếng Việt.
- **PDF sang Word:** chưa dựng lại bảng, header/footer, danh sách đánh số. Văn bản tiếng Ả Rập (viết từ phải sang trái) có thể hiển thị sai thứ tự.
- **OCR:** chữ Latin (có tiếng Việt). Chữ viết tay và ảnh chụp mờ cho kết quả kém.
- Tài liệu cực lớn cần nhiều bộ nhớ (mục 10).

---

## 12. Giấy phép bên thứ ba

SDK chứa mã nguồn mở của bên thứ ba: Apache POI, dom4j, AChartEngine, PDFium, FreeHEP, chardet của Mozilla, một phần thư viện lớp OpenJDK. Các giấy phép này (Apache 2.0, BSD, LGPL, MPL, GPL kèm Classpath Exception) yêu cầu **app phát hành phải kèm thông báo giấy phép**. Hãy đưa nội dung file `THIRD_PARTY_NOTICES.md` (được gửi kèm SDK) vào màn "Giấy phép mã nguồn mở" hoặc "Giới thiệu" của app.

---

## 13. Tra cứu API

Tất cả nằm trong package `com.editor.docsdk`.

### `DocumentViewer` (object)

| Hàm | Mô tả |
|---|---|
| `open(context, uri, options = Options())` | Mở màn xem với `Uri`. |
| `open(context, file, options = Options())` | Mở màn xem với file. |
| `intent(context, uri, options = Options()): Intent` | `Intent` của màn xem. |
| `canOpen(fileName): Boolean` | SDK có mở được file có tên này không. |
| `Options(title: String? = null, password: String? = null, editFeatures: Set<EditFeature> = emptySet())` | Tiêu đề hiển thị, mật khẩu thử trước, các tính năng sửa (rỗng: chỉ xem). |

### `DocumentView` (View)

Xem mục 4.3 và 4.4.

`DocumentView.Listener`:

| Hàm | Mô tả |
|---|---|
| `onLoaded(pageCount)` | Tài liệu đã hiển thị. |
| `onPageChanged(page, pageCount)` | Trang hiển thị đổi (đếm từ 0), hoặc số trang tăng. |
| `onError(error: DocumentException)` | Không mở được tài liệu, tài liệu lỗi sau khi đã mở (xem mục 6), hoặc không ghi được thay đổi (`STORAGE`). |
| `onEditingChanged(editing)` | Thanh sửa hiện hoặc ẩn. |
| `onSaved(file)` | Đã ghi thay đổi. |

### `EditFeature` (enum)

`TEXT`, `FORMAT`, `PARAGRAPH`, `CLIPBOARD`, `FIND_REPLACE`, `PICTURES`, `TABLES`, `ROWS_COLUMNS`, `MERGE_CELLS`, `SHEETS`, `SHAPES`, `SLIDES`, `ANIMATIONS`, `EXPORT`, `UNDO_REDO`, `SAVE_COPY`. `EditFeature.all()` trả về tất cả. Xem mục 4.4.

### `DocumentEditor`, `EditAction`, `EditDialogs`, `EditRequest`, `TableSize`

Xem mục 4.4 b) và c).

### `EditStyle`, `DialogStyle`

Giao diện thanh sửa và hộp thoại. Xem `TUY_BIEN_GIAO_DIEN.md`.

### `DocumentTools` (class, `DocumentTools(context)`)

| Hàm (`suspend`) | Trả về |
|---|---|
| `pdfToWord(input, output, password = null, progress = null)` | Số trang đã chuyển |
| `compressPdf(input, output, level = MEDIUM, password = null, progress = null)` | Số ảnh đã thu nhỏ |
| `mergePdfs(inputs, output, progress = null)` | — |
| `splitPdf(input, pages, output, password = null)` | — |
| `protectPdf(input, output, newPassword, allowPrint = true, allowCopy = true, password = null)` | — |
| `unprotectPdf(input, password, output)` | — |
| `recognizeText(input, output, password = null, progress = null)` | Số trang đã nhận dạng |
| `imagesToPdf(images, output, progress = null)` | Số trang |
| `pageCount(input, password = null)` | Số trang |
| `renderPage(input, page, widthPx, password = null)` | `Bitmap` |
| `createDocument(type, output)` | File đã tạo |

Kiểu phụ:
- `DocumentTools.Compression`: `LOW`, `MEDIUM`, `HIGH`;
- `DocumentTools.Progress`: `onProgress(done, total)`;
- `DocumentTools.Callback<T>`: `onSuccess(result)`, `onError(error)`;
- `DocumentTools.Task`: `cancel()`, `isDone`.

### `DocumentType` (enum)

`PDF`, `WORD`, `EXCEL`, `POWERPOINT`, `TEXT`. Có `fromFileName(name)` và `fromMimeType(mime)`.

### `DocumentException` (Exception)

Thuộc tính `reason: Reason`: `PASSWORD_REQUIRED`, `PASSWORD_INCORRECT`, `UNSUPPORTED`, `DAMAGED`, `NOT_FOUND`, `OUT_OF_MEMORY`, `STORAGE`, `UNKNOWN`.

---

**App mẫu:** thư mục `samples/docsdk-sample` là một project Android hoàn chỉnh, chỉ dùng SDK qua Maven. Trong đó có mở bằng màn xem, nhúng `DocumentView`, chuyển PDF sang Word, và ví dụ Java (`JavaExample.java`).
