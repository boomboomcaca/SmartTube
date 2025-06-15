package com.google.android.exoplayer2.ui;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 单词数据库类，用于管理学习中的单词
 */
public class VocabularyDatabase extends SQLiteOpenHelper {
    private static final String TAG = "VocabularyDatabase";
    
    // 数据库版本和名称
    private static final int DATABASE_VERSION = 1;
    private static final String DATABASE_NAME = "vocabulary.db";
    
    // 表名
    private static final String TABLE_WORDS = "learning_words";
    
    // 字段名
    private static final String KEY_ID = "id";
    private static final String KEY_WORD = "word";
    private static final String KEY_CREATED_AT = "created_at";
    
    // 单例实例
    private static VocabularyDatabase sInstance;
    
    /**
     * 获取VocabularyDatabase的单例实例
     * @param context 上下文
     * @return VocabularyDatabase实例
     */
    public static synchronized VocabularyDatabase getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new VocabularyDatabase(context.getApplicationContext());
            Log.d(TAG, "创建VocabularyDatabase单例实例");
        }
        return sInstance;
    }
    
    /**
     * 构造函数
     * @param context 上下文
     */
    private VocabularyDatabase(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        Log.d(TAG, "初始化VocabularyDatabase");
    }
    
    @Override
    public void onCreate(SQLiteDatabase db) {
        String CREATE_WORDS_TABLE = "CREATE TABLE " + TABLE_WORDS + "("
                + KEY_ID + " INTEGER PRIMARY KEY,"
                + KEY_WORD + " TEXT,"
                + KEY_CREATED_AT + " INTEGER" + ")";
        db.execSQL(CREATE_WORDS_TABLE);
        Log.d(TAG, "创建单词表: " + CREATE_WORDS_TABLE);
    }
    
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 如果旧表存在，则删除
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_WORDS);
        // 重新创建表
        onCreate(db);
        Log.d(TAG, "升级数据库从版本 " + oldVersion + " 到 " + newVersion);
    }
    
    /**
     * 添加单词到学习列表
     * @param word 要添加的单词
     * @return 如果添加成功，返回true
     */
    public boolean addWordToLearningList(String word) {
        if (word == null || word.trim().isEmpty()) {
            return false;
        }
        
        // 处理单词以确保一致性
        word = word.trim().toLowerCase();
        
        // 检查单词是否已存在
        if (isWordInLearningList(word)) {
            Log.d(TAG, "单词 '" + word + "' 已在学习列表中");
            return true; // 已存在视为添加成功
        }
        
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(KEY_WORD, word);
        values.put(KEY_CREATED_AT, System.currentTimeMillis());
        
        // 插入行
        long id = db.insert(TABLE_WORDS, null, values);
        Log.d(TAG, "添加单词 '" + word + "' 到学习列表, ID: " + id);
        return id != -1;
    }
    
    /**
     * 从学习列表中删除单词
     * @param word 要删除的单词
     * @return 如果删除成功，返回true
     */
    public boolean removeWordFromLearningList(String word) {
        if (word == null || word.trim().isEmpty()) {
            return false;
        }
        
        // 处理单词以确保一致性
        word = word.trim().toLowerCase();
        
        SQLiteDatabase db = this.getWritableDatabase();
        int rowsDeleted = db.delete(TABLE_WORDS, KEY_WORD + " = ?", new String[]{word});
        Log.d(TAG, "从学习列表中删除单词 '" + word + "', 删除行数: " + rowsDeleted);
        return rowsDeleted > 0;
    }
    
    /**
     * 检查单词是否在学习列表中
     * @param word 要检查的单词
     * @return 如果单词在学习列表中，返回true
     */
    public boolean isWordInLearningList(String word) {
        if (word == null || word.trim().isEmpty()) {
            return false;
        }
        
        // 处理单词以确保一致性
        word = word.trim().toLowerCase();
        
        SQLiteDatabase db = this.getReadableDatabase();
        String query = "SELECT COUNT(*) FROM " + TABLE_WORDS + " WHERE LOWER(" + KEY_WORD + ") = LOWER(?)";
        Cursor cursor = null;
        
        try {
            cursor = db.rawQuery(query, new String[]{word});
            if (cursor.moveToFirst()) {
                int count = cursor.getInt(0);
                Log.d(TAG, "检查单词 '" + word + "' 是否在学习列表中: " + (count > 0));
                return count > 0;
            }
        } catch (Exception e) {
            Log.e(TAG, "检查单词是否在学习列表中时出错: " + e.getMessage(), e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        
        return false;
    }
    
    /**
     * 获取所有学习中的单词
     * @return 学习中的单词列表
     */
    public List<String> getAllLearningWords() {
        List<String> wordList = new ArrayList<>();
        String selectQuery = "SELECT * FROM " + TABLE_WORDS + " ORDER BY " + KEY_CREATED_AT + " DESC";
        
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = null;
        
        try {
            cursor = db.rawQuery(selectQuery, null);
            
            // 遍历所有行并添加到列表
            if (cursor.moveToFirst()) {
                do {
                    String word = cursor.getString(cursor.getColumnIndex(KEY_WORD));
                    wordList.add(word);
                } while (cursor.moveToNext());
            }
            
            Log.d(TAG, "获取所有学习中的单词, 数量: " + wordList.size());
        } catch (Exception e) {
            Log.e(TAG, "获取所有学习中的单词时出错: " + e.getMessage(), e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        
        return wordList;
    }
    
    /**
     * 清空学习列表
     * @return 如果清空成功，返回true
     */
    public boolean clearLearningList() {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_WORDS, null, null);
        Log.d(TAG, "清空学习列表");
        return true;
    }
} 