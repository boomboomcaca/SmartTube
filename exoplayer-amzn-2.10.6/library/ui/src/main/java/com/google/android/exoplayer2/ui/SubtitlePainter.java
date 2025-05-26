/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.ui;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Join;
import android.graphics.Paint.Style;
import android.graphics.Rect;
import android.text.Layout.Alignment;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.DisplayMetrics;
import com.google.android.exoplayer2.text.CaptionStyleCompat;
import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.util.Log;
import com.google.android.exoplayer2.util.Util;
import com.liskovsoft.sharedutils.misc.PaddingBackgroundColorSpan;
import com.liskovsoft.sharedutils.misc.RoundedBackgroundSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Paints subtitle {@link Cue}s.
 */
/* package */ final class SubtitlePainter {

  private static final String TAG = "SubtitlePainter";

  /**
   * 字幕内容变化回调接口
   */
  public interface OnCueTextChangedListener {
    /**
     * 当字幕内容变化时调用
     * @param cue 新的字幕内容
     */
    void onCueTextChanged(Cue cue);
  }
  
  /**
   * 字幕内容变化监听器
   */
  private OnCueTextChangedListener cueTextChangedListener;
  
  /**
   * 设置字幕内容变化监听器
   * @param listener 监听器
   */
  public void setOnCueTextChangedListener(OnCueTextChangedListener listener) {
    this.cueTextChangedListener = listener;
  }

  /**
   * Ratio of inner padding to font size.
   */
  private static final float INNER_PADDING_RATIO = 0.125f;

  // 用于匹配单词的正则表达式
  private static final Pattern WORD_PATTERN = Pattern.compile("\\b\\w+\\b");
  
  // 高亮颜色
  private static final int HIGHLIGHT_COLOR = Color.RED;
  private static final int HIGHLIGHT_ALPHA = 150;
  private static final int HIGHLIGHT_TEXT_COLOR = Color.WHITE;
  private static final boolean ENABLE_FIRST_WORD_HIGHLIGHT = false; // 禁用第一个单词高亮
  public static boolean ENABLE_WORD_HIGHLIGHT = false; // 启用指定单词高亮
  
  // 当前要高亮的单词
  public String highlightWord;

  // Styled dimensions.
  private final float outlineWidth;
  private final float shadowRadius;
  private final float shadowOffset;
  private final float spacingMult;
  private final float spacingAdd;

  private final TextPaint textPaint;
  private final Paint paint;

  // Previous input variables.
  private CharSequence cueText;
  private Alignment cueTextAlignment;
  private Bitmap cueBitmap;
  private float cueLine;
  @Cue.LineType
  private int cueLineType;
  @Cue.AnchorType
  private int cueLineAnchor;
  private float cuePosition;
  @Cue.AnchorType
  private int cuePositionAnchor;
  private float cueSize;
  private float cueBitmapHeight;
  private boolean applyEmbeddedStyles;
  private boolean applyEmbeddedFontSizes;
  private int foregroundColor;
  private int backgroundColor;
  private int windowColor;
  private int edgeColor;
  @CaptionStyleCompat.EdgeType
  private int edgeType;
  private float defaultTextSizePx;
  private float cueTextSizePx;
  private float bottomPaddingFraction;
  private int parentLeft;
  private int parentTop;
  private int parentRight;
  private int parentBottom;

  // Derived drawing variables.
  private StaticLayout textLayout;
  private int textLeft;
  private int textTop;
  private int textPaddingX;
  private Rect bitmapRect;

  @SuppressWarnings("ResourceType")
  public SubtitlePainter(Context context) {
    int[] viewAttr = {android.R.attr.lineSpacingExtra, android.R.attr.lineSpacingMultiplier};
    TypedArray styledAttributes = context.obtainStyledAttributes(null, viewAttr, 0, 0);
    spacingAdd = styledAttributes.getDimensionPixelSize(0, 0);
    spacingMult = styledAttributes.getFloat(1, 1);
    styledAttributes.recycle();

    Resources resources = context.getResources();
    DisplayMetrics displayMetrics = resources.getDisplayMetrics();
    int twoDpInPx = Math.round((2f * displayMetrics.densityDpi) / DisplayMetrics.DENSITY_DEFAULT);
    outlineWidth = twoDpInPx;
    shadowRadius = twoDpInPx;
    shadowOffset = twoDpInPx;

    textPaint = new TextPaint();
    textPaint.setAntiAlias(true);
    textPaint.setSubpixelText(true);

    paint = new Paint();
    paint.setAntiAlias(true);
    paint.setStyle(Style.FILL);
  }

  /**
   * Draws the provided {@link Cue} into a canvas with the specified styling.
   *
   * <p>A call to this method is able to use cached results of calculations made during the previous
   * call, and so an instance of this class is able to optimize repeated calls to this method in
   * which the same parameters are passed.
   *
   * @param cue The cue to draw.
   * @param applyEmbeddedStyles Whether styling embedded within the cue should be applied.
   * @param applyEmbeddedFontSizes If {@code applyEmbeddedStyles} is true, defines whether font
   *     sizes embedded within the cue should be applied. Otherwise, it is ignored.
   * @param style The style to use when drawing the cue text.
   * @param defaultTextSizePx The default text size to use when drawing the text, in pixels.
   * @param cueTextSizePx The embedded text size of this cue, in pixels.
   * @param bottomPaddingFraction The bottom padding fraction to apply when {@link Cue#line} is
   *     {@link Cue#DIMEN_UNSET}, as a fraction of the viewport height
   * @param canvas The canvas into which to draw.
   * @param cueBoxLeft The left position of the enclosing cue box.
   * @param cueBoxTop The top position of the enclosing cue box.
   * @param cueBoxRight The right position of the enclosing cue box.
   * @param cueBoxBottom The bottom position of the enclosing cue box.
   */
  public void draw(
      Cue cue,
      boolean applyEmbeddedStyles,
      boolean applyEmbeddedFontSizes,
      CaptionStyleCompat style,
      float defaultTextSizePx,
      float cueTextSizePx,
      float bottomPaddingFraction,
      Canvas canvas,
      int cueBoxLeft,
      int cueBoxTop,
      int cueBoxRight,
      int cueBoxBottom) {
    boolean isTextCue = cue.bitmap == null;
    int windowColor = Color.BLACK;
    if (isTextCue) {
      if (TextUtils.isEmpty(cue.text)) {
        // Nothing to draw.
        return;
      }
      windowColor = (cue.windowColorSet && applyEmbeddedStyles)
          ? cue.windowColor : style.windowColor;
    }
    
    // 即使文本变化，也要重新应用高亮
    boolean forceUpdate = ENABLE_WORD_HIGHLIGHT && highlightWord != null && !highlightWord.isEmpty();
    
    // 检查字幕内容是否变化
    boolean cueTextChanged = !areCharSequencesEqual(this.cueText, cue.text);
    
    // 如果字幕内容变化且有监听器，通知监听器
    if (cueTextChanged && cueTextChangedListener != null) {
      cueTextChangedListener.onCueTextChanged(cue);
    }
    
    if (!forceUpdate && areCharSequencesEqual(this.cueText, cue.text)
        && Util.areEqual(this.cueTextAlignment, cue.textAlignment)
        && this.cueBitmap == cue.bitmap
        && this.cueLine == cue.line
        && this.cueLineType == cue.lineType
        && Util.areEqual(this.cueLineAnchor, cue.lineAnchor)
        && this.cuePosition == cue.position
        && Util.areEqual(this.cuePositionAnchor, cue.positionAnchor)
        && this.cueSize == cue.size
        && this.cueBitmapHeight == cue.bitmapHeight
        && this.applyEmbeddedStyles == applyEmbeddedStyles
        && this.applyEmbeddedFontSizes == applyEmbeddedFontSizes
        && this.foregroundColor == style.foregroundColor
        && this.backgroundColor == style.backgroundColor
        && this.windowColor == windowColor
        && this.edgeType == style.edgeType
        && this.edgeColor == style.edgeColor
        && Util.areEqual(this.textPaint.getTypeface(), style.typeface)
        && this.defaultTextSizePx == defaultTextSizePx
        && this.cueTextSizePx == cueTextSizePx
        && this.bottomPaddingFraction == bottomPaddingFraction
        && this.parentLeft == cueBoxLeft
        && this.parentTop == cueBoxTop
        && this.parentRight == cueBoxRight
        && this.parentBottom == cueBoxBottom) {
      // We can use the cached layout.
      drawLayout(canvas, isTextCue);
      return;
    }

    this.cueText = cue.text;
    this.cueTextAlignment = cue.textAlignment;
    this.cueBitmap = cue.bitmap;
    this.cueLine = cue.line;
    this.cueLineType = cue.lineType;
    this.cueLineAnchor = cue.lineAnchor;
    this.cuePosition = cue.position;
    this.cuePositionAnchor = cue.positionAnchor;
    this.cueSize = cue.size;
    this.cueBitmapHeight = cue.bitmapHeight;
    this.applyEmbeddedStyles = applyEmbeddedStyles;
    this.applyEmbeddedFontSizes = applyEmbeddedFontSizes;
    this.foregroundColor = style.foregroundColor;
    this.backgroundColor = style.backgroundColor;
    this.windowColor = windowColor;
    this.edgeType = style.edgeType;
    this.edgeColor = style.edgeColor;
    this.textPaint.setTypeface(style.typeface);
    this.defaultTextSizePx = defaultTextSizePx;
    this.cueTextSizePx = cueTextSizePx;
    this.bottomPaddingFraction = bottomPaddingFraction;
    this.parentLeft = cueBoxLeft;
    this.parentTop = cueBoxTop;
    this.parentRight = cueBoxRight;
    this.parentBottom = cueBoxBottom;

    if (isTextCue) {
      setupTextLayout();
    } else {
      setupBitmapLayout();
    }
    drawLayout(canvas, isTextCue);
  }

  private void setupTextLayout() {
    int parentWidth = parentRight - parentLeft;
    int parentHeight = parentBottom - parentTop;

    textPaint.setTextSize(defaultTextSizePx);
    int textPaddingX = (int) (defaultTextSizePx * INNER_PADDING_RATIO + 0.5f);

    int availableWidth = parentWidth - textPaddingX * 2;
    if (cueSize != Cue.DIMEN_UNSET) {
      availableWidth = (int) (availableWidth * cueSize);
    }
    if (availableWidth <= 0) {
      Log.w(TAG, "Skipped drawing subtitle cue (insufficient space)");
      return;
    }

    CharSequence cueText = this.cueText;
    // Remove embedded styling or font size if requested.
    if (!applyEmbeddedStyles) {
      cueText = cueText.toString(); // Equivalent to erasing all spans.
    } else if (!applyEmbeddedFontSizes) {
      SpannableStringBuilder newCueText = new SpannableStringBuilder(cueText);
      int cueLength = newCueText.length();
      AbsoluteSizeSpan[] absSpans = newCueText.getSpans(0, cueLength, AbsoluteSizeSpan.class);
      RelativeSizeSpan[] relSpans = newCueText.getSpans(0, cueLength, RelativeSizeSpan.class);
      for (AbsoluteSizeSpan absSpan : absSpans) {
        newCueText.removeSpan(absSpan);
      }
      for (RelativeSizeSpan relSpan : relSpans) {
        newCueText.removeSpan(relSpan);
      }
      cueText = newCueText;
    } else {
      // Apply embedded styles & font size.
      if (cueTextSizePx > 0) {
        // Use a SpannableStringBuilder encompassing the whole cue text to apply the default
        // cueTextSizePx.
        SpannableStringBuilder newCueText = new SpannableStringBuilder(cueText);
        newCueText.setSpan(
            new AbsoluteSizeSpan((int) cueTextSizePx),
            /* start= */ 0,
            /* end= */ newCueText.length(),
            Spanned.SPAN_PRIORITY);
        cueText = newCueText;
      }
    }

    // 实现第一个单词高亮功能
    if (ENABLE_FIRST_WORD_HIGHLIGHT) {
      SpannableStringBuilder newCueText = new SpannableStringBuilder(cueText);
      String plainText = newCueText.toString();
      Matcher matcher = WORD_PATTERN.matcher(plainText);
      
      if (matcher.find()) {
        int start = matcher.start();
        int end = matcher.end();
        String firstWord = plainText.substring(start, end);
        
        Log.d(TAG, "高亮第一个单词: '" + firstWord + "' 位置: " + start + "-" + end);
        
        // 添加背景色高亮
        int highlightColor = HIGHLIGHT_COLOR;
        highlightColor = (highlightColor & 0x00FFFFFF) | (HIGHLIGHT_ALPHA << 24); // 设置透明度
        newCueText.setSpan(
            new BackgroundColorSpan(highlightColor),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            
        // 设置文字颜色为白色，使其在红色背景上更清晰
        newCueText.setSpan(
            new ForegroundColorSpan(HIGHLIGHT_TEXT_COLOR),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            
        // 添加粗体样式
        newCueText.setSpan(
            new StyleSpan(android.graphics.Typeface.BOLD),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      }
      
      cueText = newCueText;
    }
    
    // 实现指定单词高亮功能
    if (ENABLE_WORD_HIGHLIGHT && highlightWord != null && !highlightWord.isEmpty()) {
      SpannableStringBuilder newCueText = new SpannableStringBuilder(cueText);
      String plainText = newCueText.toString();
      
      Log.d(TAG, "字幕文本: '" + plainText + "', 高亮单词: '" + highlightWord + "'");
      
      // 使用与 SubtitleWordSelectionController 一致的分词方式
      String[] words = plainText.split("\\s+");
      
      // 检测文本是否包含CJK字符（中文、日文、韩文）
      boolean containsCJK = false;
      for (int i = 0; i < plainText.length(); i++) {
          char c = plainText.charAt(i);
          // 中文范围: \u4E00-\u9FFF, 日文片假名: \u3040-\u309F, 韩文: \uAC00-\uD7A3 等
          if ((c >= '\u4E00' && c <= '\u9FFF') || // 中文
              (c >= '\u3040' && c <= '\u30FF') || // 日文平假名和片假名
              (c >= '\uAC00' && c <= '\uD7A3')) { // 韩文
              containsCJK = true;
              break;
          }
      }

      if (containsCJK) {
          // CJK文字处理：逐字分词
          int position = 0;
          
          for (int i = 0; i < plainText.length(); i++) {
              char c = plainText.charAt(i);
              
              // 如果是CJK字符，则作为单独的词
              if ((c >= '\u4E00' && c <= '\u9FFF') || // 中文
                  (c >= '\u3040' && c <= '\u30FF') || // 日文平假名和片假名
                  (c >= '\uAC00' && c <= '\uD7A3')) { // 韩文
                  
                  // 当前字符作为单词
                  String currentChar = String.valueOf(c);
                  
                  // 如果当前字符与目标单词匹配
                  if (currentChar.equals(highlightWord)) {
                      Log.d(TAG, "高亮CJK字符: '" + currentChar + "' 位置: " + i);
                      
                      // 添加背景色高亮
                      int highlightColor = HIGHLIGHT_COLOR;
                      highlightColor = (highlightColor & 0x00FFFFFF) | (HIGHLIGHT_ALPHA << 24); // 设置透明度
                      newCueText.setSpan(
                          new BackgroundColorSpan(highlightColor),
                          i,
                          i + 1,
                          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                          
                      // 设置文字颜色为白色，使其在红色背景上更清晰
                      newCueText.setSpan(
                          new ForegroundColorSpan(HIGHLIGHT_TEXT_COLOR),
                          i,
                          i + 1,
                          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                          
                      // 添加粗体样式
                      newCueText.setSpan(
                          new StyleSpan(android.graphics.Typeface.BOLD),
                          i,
                          i + 1,
                          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                  }
              }
          }
      } else {
          // 非CJK文字处理：使用原有的空格分词方式
          int position = 0;
          for (String word : words) {
              // 跳过空白字符
              while (position < plainText.length() && Character.isWhitespace(plainText.charAt(position))) {
                  position++;
              }
              
              if (position < plainText.length()) {
                  // 清理单词（去除标点符号等）以便比较
                  String cleanWord = word.replaceAll("[,.!?;:'\"]", "").trim();
                  
                  if (!cleanWord.isEmpty()) {
                      // 记录原始单词在文本中的位置（包含标点符号）
                      int start = position;
                      int end = position + word.length();
                      
                      // 如果当前单词（不考虑大小写）与目标单词匹配
                      if (cleanWord.equalsIgnoreCase(highlightWord)) {
                          Log.d(TAG, "高亮单词: '" + cleanWord + "' 位置: " + start + "-" + end + " 原始单词: '" + word + "'");
                          
                          // 添加背景色高亮
                          int highlightColor = HIGHLIGHT_COLOR;
                          highlightColor = (highlightColor & 0x00FFFFFF) | (HIGHLIGHT_ALPHA << 24); // 设置透明度
                          newCueText.setSpan(
                              new BackgroundColorSpan(highlightColor),
                              start,
                              end,
                              Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                              
                          // 设置文字颜色为白色，使其在红色背景上更清晰
                          newCueText.setSpan(
                              new ForegroundColorSpan(HIGHLIGHT_TEXT_COLOR),
                              start,
                              end,
                              Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                              
                          // 添加粗体样式
                          newCueText.setSpan(
                              new StyleSpan(android.graphics.Typeface.BOLD),
                              start,
                              end,
                              Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                      }
                      
                      position += word.length();
                  }
              }
          }
      }
      
      cueText = newCueText;
    } else if (ENABLE_WORD_HIGHLIGHT) {
      // 如果启用了高亮功能但没有指定高亮单词，记录日志以便调试
      Log.d(TAG, "高亮功能已启用，但未指定高亮单词或单词为空");
    }

    if (Color.alpha(backgroundColor) > 0) {
      SpannableStringBuilder newCueText = new SpannableStringBuilder(cueText);
      // MOD: add subs bg padding
      //newCueText.setSpan(
      //    new BackgroundColorSpan(backgroundColor), 0, newCueText.length(), Spanned.SPAN_PRIORITY);
      newCueText.setSpan(
              new PaddingBackgroundColorSpan(backgroundColor), 0, newCueText.length(), Spanned.SPAN_PRIORITY);
      //newCueText.setSpan(
      //        new RoundedBackgroundSpan(backgroundColor), 0, newCueText.length(), Spanned.SPAN_PRIORITY);
      cueText = newCueText;
    }

    Alignment textAlignment = cueTextAlignment == null ? Alignment.ALIGN_CENTER : cueTextAlignment;
    textLayout = new StaticLayout(cueText, textPaint, availableWidth, textAlignment, spacingMult,
        spacingAdd, true);
    // MOD: same height for multiline and single line subs (use 3 lines height)
    //int textHeight = textLayout.getLineBaseline(0) * 3; // base line has same height across single and multi line
    int textHeight = textLayout.getHeight();
    int textWidth = 0;
    int lineCount = textLayout.getLineCount();
    for (int i = 0; i < lineCount; i++) {
      textWidth = Math.max((int) Math.ceil(textLayout.getLineWidth(i)), textWidth);
    }
    if (cueSize != Cue.DIMEN_UNSET && textWidth < availableWidth) {
      textWidth = availableWidth;
    }
    textWidth += textPaddingX * 2;

    int textLeft;
    int textRight;
    if (cuePosition != Cue.DIMEN_UNSET) {
      int anchorPosition = Math.round(parentWidth * cuePosition) + parentLeft;
      textLeft = cuePositionAnchor == Cue.ANCHOR_TYPE_END ? anchorPosition - textWidth
          : cuePositionAnchor == Cue.ANCHOR_TYPE_MIDDLE ? (anchorPosition * 2 - textWidth) / 2
              : anchorPosition;
      textLeft = Math.max(textLeft, parentLeft);
      textRight = Math.min(textLeft + textWidth, parentRight);
    } else {
      textLeft = (parentWidth - textWidth) / 2 + parentLeft;
      textRight = textLeft + textWidth;
    }

    textWidth = textRight - textLeft;
    if (textWidth <= 0) {
      Log.w(TAG, "Skipped drawing subtitle cue (invalid horizontal positioning)");
      return;
    }

    int textTop;
    if (cueLine != Cue.DIMEN_UNSET) {
      int anchorPosition;
      if (cueLineType == Cue.LINE_TYPE_FRACTION) {
        anchorPosition = Math.round(parentHeight * cueLine) + parentTop;
      } else {
        // cueLineType == Cue.LINE_TYPE_NUMBER
        int firstLineHeight = textLayout.getLineBottom(0) - textLayout.getLineTop(0);
        if (cueLine >= 0) {
          anchorPosition = Math.round(cueLine * firstLineHeight) + parentTop;
        } else {
          anchorPosition = Math.round((cueLine + 1) * firstLineHeight) + parentBottom;
        }
      }
      textTop = cueLineAnchor == Cue.ANCHOR_TYPE_END ? anchorPosition - textHeight
          : cueLineAnchor == Cue.ANCHOR_TYPE_MIDDLE ? (anchorPosition * 2 - textHeight) / 2
              : anchorPosition;
      if (textTop + textHeight > parentBottom) {
        textTop = parentBottom - textHeight;
      } else if (textTop < parentTop) {
        textTop = parentTop;
      }
    } else {
      textTop = parentBottom - textHeight - (int) (parentHeight * bottomPaddingFraction);
    }

    // Update the derived drawing variables.
    this.textLayout = new StaticLayout(cueText, textPaint, textWidth, textAlignment, spacingMult,
        spacingAdd, true);
    this.textLeft = textLeft;
    this.textTop = textTop;
    this.textPaddingX = textPaddingX;
  }

  private void setupBitmapLayout() {
    int parentWidth = parentRight - parentLeft;
    int parentHeight = parentBottom - parentTop;
    float anchorX = parentLeft + (parentWidth * cuePosition);
    float anchorY = parentTop + (parentHeight * cueLine);
    int width = Math.round(parentWidth * cueSize);
    int height = cueBitmapHeight != Cue.DIMEN_UNSET ? Math.round(parentHeight * cueBitmapHeight)
        : Math.round(width * ((float) cueBitmap.getHeight() / cueBitmap.getWidth()));
    int x =
        Math.round(
            cuePositionAnchor == Cue.ANCHOR_TYPE_END
                ? (anchorX - width)
                : cuePositionAnchor == Cue.ANCHOR_TYPE_MIDDLE ? (anchorX - (width / 2)) : anchorX);
    int y =
        Math.round(
            cueLineAnchor == Cue.ANCHOR_TYPE_END
                ? (anchorY - height)
                : cueLineAnchor == Cue.ANCHOR_TYPE_MIDDLE ? (anchorY - (height / 2)) : anchorY);
    bitmapRect = new Rect(x, y, x + width, y + height);
  }

  private void drawLayout(Canvas canvas, boolean isTextCue) {
    if (isTextCue) {
      drawTextLayout(canvas);
    } else {
      drawBitmapLayout(canvas);
    }
  }

  private void drawTextLayout(Canvas canvas) {
    StaticLayout layout = textLayout;
    if (layout == null) {
      // Nothing to draw.
      return;
    }

    int saveCount = canvas.save();
    canvas.translate(textLeft, textTop);

    if (Color.alpha(windowColor) > 0) {
      paint.setColor(windowColor);
      canvas.drawRect(-textPaddingX, 0, layout.getWidth() + textPaddingX, layout.getHeight(),
          paint);
    }

    if (edgeType == CaptionStyleCompat.EDGE_TYPE_OUTLINE) {
      textPaint.setStrokeJoin(Join.ROUND);
      textPaint.setStrokeWidth(outlineWidth);
      textPaint.setColor(edgeColor);
      textPaint.setStyle(Style.FILL_AND_STROKE);
      layout.draw(canvas);
    } else if (edgeType == CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW) {
      textPaint.setShadowLayer(shadowRadius, shadowOffset, shadowOffset, edgeColor);
    } else if (edgeType == CaptionStyleCompat.EDGE_TYPE_RAISED
        || edgeType == CaptionStyleCompat.EDGE_TYPE_DEPRESSED) {
      boolean raised = edgeType == CaptionStyleCompat.EDGE_TYPE_RAISED;
      int colorUp = raised ? Color.WHITE : edgeColor;
      int colorDown = raised ? edgeColor : Color.WHITE;
      float offset = shadowRadius / 2f;
      textPaint.setColor(foregroundColor);
      textPaint.setStyle(Style.FILL);
      textPaint.setShadowLayer(shadowRadius, -offset, -offset, colorUp);
      layout.draw(canvas);
      textPaint.setShadowLayer(shadowRadius, offset, offset, colorDown);
    }

    textPaint.setColor(foregroundColor);
    textPaint.setStyle(Style.FILL);
    layout.draw(canvas);
    textPaint.setShadowLayer(0, 0, 0, 0);

    canvas.restoreToCount(saveCount);
  }

  private void drawBitmapLayout(Canvas canvas) {
    canvas.drawBitmap(cueBitmap, null, bitmapRect, null);
  }

  /**
   * This method is used instead of {@link TextUtils#equals(CharSequence, CharSequence)} because the
   * latter only checks the text of each sequence, and does not check for equality of styling that
   * may be embedded within the {@link CharSequence}s.
   */
  @SuppressWarnings("UndefinedEquals")
  private static boolean areCharSequencesEqual(CharSequence first, CharSequence second) {
    // Some CharSequence implementations don't perform a cheap referential equality check in their
    // equals methods, so we perform one explicitly here.
    return first == second || (first != null && first.equals(second));
  }

}
