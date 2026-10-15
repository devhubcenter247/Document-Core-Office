# DocSDK — Tuỳ biến giao diện sửa tài liệu

Có ba mức tuỳ biến, từ ít đến nhiều công sức:

| Muốn | Dùng | Xem |
|---|---|---|
| Đổi màu, cỡ chữ, font của thanh sửa có sẵn và của con trỏ, tay nắm, khung chọn | `EditStyle` | mục 2, 3 |
| Đổi màu, cỡ chữ của dialog SDK | `DialogStyle` | mục 4 |
| Tự làm dialog (chọn màu, cỡ chữ, font…) | `EditDialogs` | mục 4.4 c) của `HUONG_DAN_TICH_HOP.md` |
| Tự làm cả thanh sửa, chọn lệnh nào có nút | `startEditing(showToolbar = false)`, `DocumentEditor`, `EditAction` | mục 4.4 b) của `HUONG_DAN_TICH_HOP.md` |
| Ẩn tính năng | `EditFeature` | mục 4.4 của `HUONG_DAN_TICH_HOP.md` |
| Đổi chữ, thêm ngôn ngữ | chuỗi `docsdk_edit_*` | mục 8 của `HUONG_DAN_TICH_HOP.md` |

`EditStyle` và `DialogStyle` là `data class` có sẵn giá trị mặc định, đổi bằng **code**. Bạn gán một `customizer` **một lần**, thường trong `Application.onCreate`. `customizer` nhận `context` và style mặc định, rồi trả về style muốn dùng. Nhờ có `context`, bạn đọc được màu theo theme sáng/tối của app.

---

## 1. Bắt đầu nhanh

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        EditStyle.customizer = EditStyle.Customizer { context, style ->
            val brand = ContextCompat.getColor(context, R.color.brand)
            style.copy(accent = brand, iconActive = brand, selection = brand)
        }

        DialogStyle.customizer = DialogStyle.Customizer { context, style ->
            style.copy(accent = ContextCompat.getColor(context, R.color.brand), cornerDp = 8f)
        }
    }
}
```

Chỉ cần đổi những thuộc tính muốn đổi. Các thuộc tính còn lại giữ mặc định.

> Style được đọc **mỗi khi một thanh sửa hoặc hộp thoại mở ra**. Đổi `customizer` lúc đang sửa thì thanh đang mở không đổi. Thanh mở lần sau sẽ dùng style mới.

**Java**

```java
EditStyle.setCustomizer((context, style) -> new EditStyle(
        Color.WHITE,                                     // toolbarBackground
        Color.parseColor("#E0E0E0"),                     // divider
        Color.parseColor("#333333"),                     // icon
        ContextCompat.getColor(context, R.color.brand)   // iconActive
        // các tham số sau giữ mặc định
));

DialogStyle.setCustomizer((context, style) -> new DialogStyle(
        style.getBackground(), style.getTitle(), style.getText(), style.getCaption(),
        ContextCompat.getColor(context, R.color.brand),  // accent
        style.getDivider(), style.getRowPressed()));
```

Muốn bỏ tuỳ biến, gán `customizer = null`.

---

## 2. `EditStyle`: thanh sửa có sẵn

Thanh sửa gồm: hàng trên cùng (Hoàn tác, Làm lại, dòng trạng thái, Lưu bản sao, nút **Lưu**), ô công thức của Excel, hàng tab, và hàng icon của tab đang chọn.

| Thuộc tính | Mặc định | Ý nghĩa |
|---|---|---|
| `toolbarBackground` | `#FFFFFF` | Nền thanh |
| `divider` | `#E3E3E3` | Đường kẻ giữa các phần của thanh |
| `icon` | `#444746` | Màu icon |
| `iconActive` | `#0B57D0` | Màu icon của lệnh đang bật (chữ đậm đang chọn…) |
| `activeBackground` | `#D3E3FD` | Nền của lệnh đang bật |
| `deleteIcon` | `#B3261E` | Màu icon các lệnh xoá |
| `accent` | `#0B57D0` | Chữ và gạch dưới của tab đang chọn, nền nút **Lưu**, dấu ✓ của ô công thức |
| `onAccent` | `#FFFFFF` | Chữ nút **Lưu** |
| `tabText` | `#5F6368` | Chữ tab chưa chọn |
| `tabTextSp` | `14` | Cỡ chữ tab |
| `typeface` | `Typeface.DEFAULT` | Font của tab, dòng trạng thái, nút **Lưu** |
| `iconDp` | `24` | Cỡ icon |
| `buttonDp` | `44` | Cỡ ô vuông của mỗi nút icon (vùng chạm) |
| `labelText` | `#5F6368` | Dòng trạng thái, tên ô Excel |
| `labelTextSp` | `13` | Cỡ chữ dòng trạng thái |
| `inputText` | `null` | Chữ ô công thức Excel. `null` giữ màu của theme |
| `inputTextSp` | `15` | Cỡ chữ ô công thức |
| `inputHint` | `null` | Chữ gợi ý của ô công thức. `null` giữ màu của theme |
| `inputLine` | `null` | Gạch chân ô công thức. `null` giữ màu của theme |
| `selection` | `#1A73E8` | Con trỏ chữ, tay nắm bôi chọn, khung ảnh/hình/bảng (dùng cả khi app tự làm thanh) |
| `handleFill` | `#FFFFFF` | Ruột các tay nắm tròn để đổi kích thước và xoay |
| `dropTarget` | `#E8453C` | Vạch chỉ chỗ ảnh sẽ rơi xuống khi kéo ảnh trong Word |
| `itemStyler` | `null` | Hàm chỉnh thêm từng nút (mục 3) |
| `icons` | `null` | Icon riêng cho từng nút và dấu ✓ của ô công thức. `null` dùng icon của SDK |
| `backIcon` | `null` | Icon nút quay lại của màn `DocumentViewer`. `null` dùng icon của SDK |
| `inputStyler` | `null` | Hàm chỉnh thêm ô công thức Excel (mục "Ô nhập liệu") |
| `textBarBackground` | `null` | Nền thanh ngữ cảnh (mục 3b). `null` dùng `toolbarBackground` |
| `textBarButtonDp` | `36` | Chiều cao mỗi nút của thanh ngữ cảnh; thanh cao thêm 4dp trên và dưới |
| `textBarIconDp` | `20` | Cỡ icon của thanh ngữ cảnh |
| `textBarSpacingDp` | `6` | Khoảng cách giữa các nút của thanh ngữ cảnh |

### Ví dụ: thanh sửa tối

`EditStyle` mặc định không đổi theo theme tối. Nếu app có giao diện tối, hãy tự chọn màu theo `context`:

```kotlin
EditStyle.customizer = EditStyle.Customizer { context, style ->
    val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    if (!night) style else style.copy(
        toolbarBackground = 0xFF1F1F1F.toInt(),
        divider = 0xFF3A3A3A.toInt(),
        icon = 0xFFE3E3E3.toInt(),
        iconActive = 0xFFA8C7FA.toInt(),
        activeBackground = 0xFF0842A0.toInt(),
        deleteIcon = 0xFFF2B8B5.toInt(),
        accent = 0xFFA8C7FA.toInt(),
        onAccent = 0xFF062E6F.toInt(),
        tabText = 0xFFC4C7C5.toInt(),
        labelText = 0xFFC4C7C5.toInt(),
        inputText = 0xFFE3E3E3.toInt(),
        selection = 0xFF8AB4F8.toInt(),
        handleFill = 0xFF1F1F1F.toInt(),
    )
}
```

### Icon riêng: `icons`

Mặc định mọi app dùng SDK có cùng bộ icon. Để dùng icon của app, truyền một `EditStyle.Icons`: nó nhận `EditAction` của nút và trả về id drawable, hoặc `null` để giữ icon của SDK (`EditAction.icon`).

```kotlin
EditStyle.customizer = EditStyle.Customizer { _, style ->
    style.copy(icons = EditStyle.Icons { action ->
        when (action) {
            EditAction.BOLD -> R.drawable.ic_my_bold
            EditAction.ITALIC -> R.drawable.ic_my_italic
            else -> null
        }
    })
}
```

**Java**

```java
EditStyle.setCustomizer((context, style) -> new EditStyle(
        style.getToolbarBackground(), style.getDivider(), style.getIcon(), style.getIconActive(),
        style.getActiveBackground(), style.getDeleteIcon(), style.getAccent(), style.getOnAccent(),
        style.getTabText(), style.getTabTextSp(), style.getTypeface(), style.getIconDp(), style.getButtonDp(),
        style.getLabelText(), style.getLabelTextSp(), style.getInputText(), style.getInputTextSp(),
        style.getSelection(), style.getHandleFill(), style.getDropTarget(), style.getItemStyler(),
        action -> action == EditAction.BOLD ? R.drawable.ic_my_bold : null));
```

Lưu ý:

- Icon vẫn được tô bằng `icon`, `iconActive`, `deleteIcon` như icon của SDK, nên hãy dùng icon một màu (vector trắng hoặc đen đều được). Cỡ hiển thị là `iconDp`.
- Dấu ✓ của ô công thức Excel lấy icon của `EditAction.CELL_VALUE` (lệnh ghi giá trị ô).
- Nút quay lại của màn `DocumentViewer` đặt bằng `backIcon`. Icon này được vẽ nguyên màu, không tô.

```kotlin
style.copy(backIcon = R.drawable.ic_my_back)
```

### Ô nhập liệu

Ô công thức Excel lấy `typeface`, `inputText`, `inputTextSp`, `inputHint`, `inputLine` của `EditStyle`. Mọi ô nhập trong hộp thoại (tìm & thay, thêm sheet, chọn vùng, cỡ chữ… của thanh sửa; mật khẩu, ghi chú, watermark, header/footer, bookmark, ô form… của công cụ PDF; mật khẩu của `DocumentViewer`) lấy `text`, `caption` (gợi ý), `accent` (gạch chân), `textSp`, `textTypeface` của `DialogStyle`.

Để chỉnh sâu hơn (nền có viền, bo góc, lề trong, màu con trỏ…), truyền một `InputStyler`: `EditStyle.inputStyler` cho ô công thức, `DialogStyle.inputStyler` cho ô trong hộp thoại. Nó được gọi sau khi màu đã đặt.

```kotlin
val boxed = InputStyler { input ->
    val dp = input.resources.displayMetrics.density
    input.background = GradientDrawable().apply {
        setStroke(dp.toInt(), brand); cornerRadius = 8 * dp
    }
    input.setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
}
EditStyle.customizer = EditStyle.Customizer { _, style -> style.copy(inputStyler = boxed) }
DialogStyle.customizer = DialogStyle.Customizer { _, style -> style.copy(inputStyler = boxed) }
```

**Không** đổi chữ, listener hay `inputType` của ô: ô sẽ mất chức năng. Ô sửa chữ trực tiếp trên slide PowerPoint không theo style này: nó lấy font, cỡ và màu của chính khung chữ.

---

## 3. Chỉnh sâu từng nút: `itemStyler`

Nếu màu và kích thước chưa đủ, ví dụ bạn muốn nền riêng, hiệu ứng ripple khác hay ẩn vài nút, hãy truyền một `EditStyle.ItemStyler`. Nó được gọi trên **mọi nút** của thanh (kể cả nút **Lưu**) ngay sau khi nút được tạo, kèm `EditAction` của nút đó. Các nút của thanh ngữ cảnh (mục 3b) cũng đi qua nó. Ở thanh này, nút đang bật (B, I, U, căn lề) chỉ được đặt `isSelected = true`, nên nền và màu icon bạn đặt vẫn giữ nguyên. Muốn nút có màu riêng khi bật thì dùng drawable/`ColorStateList` có `state_selected`.

```kotlin
EditStyle.customizer = EditStyle.Customizer { context, style ->
    style.copy(itemStyler = EditStyle.ItemStyler { button, action ->
        when (action) {
            EditAction.SAVE -> button.background = AppCompatResources.getDrawable(context, R.drawable.bg_save)
            EditAction.EXPORT_PNG, EditAction.EXPORT_PDF -> button.visibility = View.GONE
            else -> {}
        }
    })
}
```

**Java**

```java
EditStyle.setCustomizer((context, style) -> new EditStyle(
        style.getToolbarBackground(), style.getDivider(), style.getIcon(), style.getIconActive(),
        style.getActiveBackground(), style.getDeleteIcon(), style.getAccent(), style.getOnAccent(),
        style.getTabText(), style.getTabTextSp(), style.getTypeface(), style.getIconDp(), style.getButtonDp(),
        style.getLabelText(), style.getLabelTextSp(), style.getInputText(), style.getInputTextSp(),
        style.getSelection(), style.getHandleFill(), style.getDropTarget(),
        (button, action) -> { if (action == EditAction.SAVE) button.setBackgroundResource(R.drawable.bg_save); }));
```

Lưu ý:

- **Không** gọi `setOnClickListener` của nút: nút sẽ mất chức năng.
- Nút icon là một `FrameLayout` chứa một `ImageView`. Nút **Lưu** là một `TextView`. `tag` của mỗi nút là tên của `EditAction` (ví dụ `"BOLD"`).
- Nền của nút icon được SDK đặt lại khi lệnh bật hoặc tắt (để tô nền lệnh đang bật). Nếu bạn cần nền riêng cho mọi trạng thái, hãy tự làm thanh (mục 4.4 b của `HUONG_DAN_TICH_HOP.md`).
- Hàm được gọi trên luồng chính, mỗi lần một thanh sửa mở ra. Hãy để nó chạy nhanh: không đọc file, không gọi mạng.

---

## 3b. Thanh ngữ cảnh

Khi người dùng chọn một thứ trong tài liệu, SDK hiện một hàng nút ngay trên bàn phím (hoặc trên thanh sửa) với đúng các lệnh dùng được cho thứ đó. Không còn menu nổi Cắt/Chép/Dán trên vùng chọn.

| Ngữ cảnh (`EditContext`) | Khi nào | Nút mặc định |
|---|---|---|
| `TEXT` | Word: con trỏ hoặc chữ đang bôi | Cắt, Chép, Dán, B, I, U, màu chữ, tô sáng, font, − cỡ +, căn lề, gạch đầu dòng, đánh số |
| `TABLE` | Word: con trỏ hoặc vùng chọn trong bảng | Chọn bảng, thêm hàng trên/dưới, thêm cột trái/phải, xoá hàng, xoá cột, Cắt, Chép, Dán, rồi các nút định dạng chữ |
| `PICTURE` | Word: ảnh đang chọn | Xoá |
| `CELLS` | Excel: ô hoặc vùng ô đang chọn | Cắt, Chép, Dán, Xoá nội dung, B, I, U, màu chữ, màu nền, căn lề, xuống dòng, gộp ô, viền, định dạng số |
| `SHAPE` | PowerPoint: hình đang chọn | Cắt, Chép, Dán, Xoá, Xoay; hình có chữ thêm B, I, U, màu, cỡ, căn lề (áp cho cả chữ của hình) |
| `SHAPE_TEXT` | PowerPoint: đang gõ chữ trong hình | `textFormatBarActions` (B, I, U, màu, font, − cỡ +, căn lề) |

Nút không dùng được lúc đó (Chép khi chưa bôi gì, Dán khi clipboard trống) bị làm mờ. Nhấn giữ một nút để xem tên lệnh.

```kotlin
// đổi nút của một ngữ cảnh (danh sách rỗng: ẩn thanh ở ngữ cảnh đó)
editor.contextCommands = DocumentEditor.ContextCommands { context, defaults ->
    when (context) {
        EditContext.PICTURE -> emptyList()
        EditContext.TABLE -> defaults.take(7)
        else -> defaults
    }
}
// hoặc tắt hẳn và tự làm thanh: đọc editor.context, editor.contextActions(),
// isEnabled / isActive trong Listener.onStateChanged, rồi editor.run(action)
editor.contextBar = false
```

## 4. `DialogStyle`: hộp thoại

| Thuộc tính | Mặc định (sáng / tối) | Ý nghĩa |
|---|---|---|
| `background` | `#FFFFFF` / `#262626` | Nền hộp thoại |
| `title` | `#1F1F1F` / `#F2F2F2` | Tiêu đề |
| `text` | `#2B2B2B` / `#E0E0E0` | Chữ nội dung, các dòng lựa chọn |
| `caption` | `#6B6B6B` / `#A0A0A0` | Chú thích nhóm, gợi ý ô nhập, ô chưa chọn |
| `accent` | `#1A73E8` / `#8AB4F8` | Nút OK/Huỷ, ô và lựa chọn đang chọn, thanh tiến trình |
| `divider` | `#E8E8E8` / `#3A3A3A` | Đường kẻ, viền ô màu |
| `rowPressed` | `#14000000` / `#1FFFFFFF` | Hiệu ứng khi nhấn một dòng |
| `cornerDp` | `16` | Bo góc cửa sổ |
| `titleSp` / `textSp` / `captionSp` | `18` / `15` / `13` | Cỡ chữ |
| `paddingDp` | `20` | Lề trong hộp thoại |
| `rowPaddingDp` | `12` | Lề trên/dưới của mỗi dòng |
| `titleTypeface` / `textTypeface` | `DEFAULT_BOLD` / `DEFAULT` | Font |
| `inputStyler` | `null` | Hàm chỉnh thêm ô nhập (mục 2, "Ô nhập liệu") |

Màu mặc định tự theo theme sáng/tối của máy. Nếu chỉ đổi `accent`, hộp thoại vẫn đổi sáng/tối như cũ:

```kotlin
DialogStyle.customizer = DialogStyle.Customizer { context, style ->
    style.copy(accent = ContextCompat.getColor(context, R.color.brand))
}
```

Muốn biết style mặc định (chưa qua `customizer`) của một `context`, gọi `DialogStyle.defaults(context)`. `DialogStyle.of(context)` và `EditStyle.of(context)` trả về style thật sự được dùng.

Muốn thay hẳn một dialog (không chỉ đổi màu), dùng `EditDialogs` (mục 4.4 c của `HUONG_DAN_TICH_HOP.md`).

---

## 5. Câu hỏi thường gặp

**Đổi style rồi mà thanh sửa không đổi?**
Style chỉ được đọc khi thanh mở ra. Hãy đóng thanh rồi mở lại. Ngoài ra, kiểm tra xem `customizer` đã được gán trước khi bắt đầu sửa chưa.

**Có đổi được tên các nút và tab không?**
Có. Khai báo lại chuỗi `docsdk_edit_*` cùng tên trong `res/values/strings.xml` của app (mục 8 của `HUONG_DAN_TICH_HOP.md`). Tên nút cũng là chữ hiện khi nhấn giữ nút và chữ TalkBack đọc.

**Có ẩn được nút không?**
Có. Tắt cả nhóm bằng `EditFeature` khi gọi `startEditing`. Ẩn từng nút bằng `itemStyler` (mục 3). Hoặc tự làm thanh với đúng các lệnh muốn có.

**Có đổi thứ tự nút, tab không?**
Thanh có sẵn giữ thứ tự của SDK. Muốn thứ tự khác, hãy tự làm thanh với `DocumentEditor` (mục 4.4 b của `HUONG_DAN_TICH_HOP.md`).
