# ClassMate Design System & Figma Integration Rules

Definitive rules and mapping architecture for translating Figma designs into the ClassMate Android client and Web companion client using the Model Context Protocol (MCP).

---

## 1. Design System Tokens & Semantic Mapping

### Android (`app/src/main/res/values/`)
* **Color Tokens** (`colors.xml`, `colors_tokens.xml`):
  * Primary: `@color/cm_primary` (`#2563EB`) / Primary Dark: `#1D4ED8`
  * Text Primary: `@color/cm_text_primary` (`#0F172A`)
  * Text Secondary / Subtitles: `@color/cm_text_secondary` (`#64748B`)
  * Text Muted / Footnotes: `@color/cm_text_muted` (`#94A3B8`)
  * App Background: `@color/cm_background` (`#F8FAFC`)
  * Card Surface: `@color/cm_card_surface` (`#FFFFFF`)
  * Urgent / Cancelled Accent: `@color/cm_notice_premium_cancel` (`#EF4444`)
  * Resource / File Accent: `@color/cm_notice_premium_resource` (`#10B981`)
  * Mutual Aid / Community Accent: `@color/cm_notice_premium_accent` (`#7C3AED`)
* **Typography**:
  * Brand & Headlines: `Plus Jakarta Sans` (`@font/plus_jakarta_sans_extrabold`, `@font/plus_jakarta_sans_bold`)
  * Body / Preview: `sans-serif-medium` / `Roboto` (15sp, `lineSpacingExtra="3dp"`)
  * Bengali Script: Noto Sans Bengali / Hind Siliguri (`includeFontPadding="false"`)
* **Shapes & Corners**:
  * Feed Cards: `20dp`–`24dp` (`@style/RoundedCornersCard`, `app:cardCornerRadius="20dp"`)
  * Action Pills / Filter Chips: `999dp` capsule (`@drawable/bg_notice_action_pill`)
  * Accent Indicator: `4dp` width, dynamic vertical fill (`minHeight="22dp"`)

### Web Companion (`web/src/app/globals.css`, `tailwind.config.ts`)
* Uses matching Tailwind tokens: `bg-slate-50` / `bg-white`, `text-slate-900` / `text-slate-500`, `border-slate-200`.

---

## 2. Component Hierarchy & Patterns

### A. Notice Card Architecture (`item_notice_modern.xml`)
```xml
<MaterialCardView
    app:cardCornerRadius="20dp"
    app:cardElevation="0dp"
    app:strokeColor="@color/cm_notice_premium_stroke"
    app:strokeWidth="1dp">

    <!-- Top: Accent Pill + Title + Category Art + 3-Dots -->
    <LinearLayout android:orientation="horizontal">
        <View android:id="@+id/noticeAccent" android:layout_height="match_parent" android:minHeight="22dp"/>
        <TextView android:id="@+id/tvTitle" android:textSize="18sp" android:textStyle="bold"/>
        <ImageView android:id="@+id/ivNoticeIllustration"/>
        <ImageView android:id="@+id/btnOptions"/>
    </LinearLayout>

    <!-- Body Preview -->
    <TextView android:id="@+id/tvPreview" android:maxLines="5" android:ellipsize="end"/>

    <!-- Bottom Action Bar (48dp height) -->
    <!-- [Read Receipts / Avatars] ---------------- [Like] [Comment] [Share] -->
</MaterialCardView>
```

### B. Header Component (`fragment_notice.xml`)
* Hamburger menu drawer (`38dp`)
* Title (`23sp`, Plus Jakarta Sans Extrabold)
* Search icon button (`38dp` rounded pill)

---

## 3. Figma to Code Best Practices

1. **Auto-Layout Matching**: Every horizontal or vertical stack in Figma must map directly to an auto-layout container or Android `LinearLayout` / Compose `Row` / `Column`.
2. **Dynamic Height Accents**: Left accent indicators must use `match_parent` height with a `minHeight` threshold rather than static heights so multi-line text wraps cleanly.
3. **Adaptive Touch Targets**: All interactive icons must maintain at least `44dp`–`48dp` clickable boundaries with ripple drawables (`?attr/selectableItemBackgroundBorderless`).
4. **Zero-Counter Hiding**: Hide counter text when engagement count is `0`, showing only the outline glyph until engagement is recorded.
