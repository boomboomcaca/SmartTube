package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

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
        }
        return sInstance;
    }
    
    /**
     * 构造函数
     * @param context 上下文
     */
    private VocabularyDatabase(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }
    
    @Override
    public void onCreate(SQLiteDatabase db) {
        String CREATE_WORDS_TABLE = "CREATE TABLE " + TABLE_WORDS +
                "(" +
                KEY_ID + " INTEGER PRIMARY KEY," +
                KEY_WORD + " TEXT UNIQUE" +
                ")";
        db.execSQL(CREATE_WORDS_TABLE);
        Log.d(TAG, "数据库表已创建");
    }
    
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 如果有旧表，则删除
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_WORDS);
        // 重新创建表
        onCreate(db);
    }
    
    /**
     * 添加一个单词到学习列表
     * @param word 要添加的单词
     * @return 添加是否成功
     */
    public boolean addWord(String word) {
        if (word == null || word.trim().isEmpty()) {
            Log.e(TAG, "尝试添加空单词");
            return false;
        }
        
        // 处理单词以确保一致性
        word = word.trim().toLowerCase();
        
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(KEY_WORD, word);
        
        // 插入行，如果单词已存在则忽略
        long id = db.insertWithOnConflict(TABLE_WORDS, null, values, SQLiteDatabase.CONFLICT_IGNORE);
        
        if (id == -1) {
            Log.e(TAG, "添加单词失败: " + word);
            return false;
        }
        
        Log.d(TAG, "单词已添加/更新: " + word);
        return true;
    }
    
    /**
     * 从学习列表中删除单词
     * @param word 要删除的单词
     * @return 删除是否成功
     */
    public boolean removeWord(String word) {
        if (word == null || word.trim().isEmpty()) {
            Log.e(TAG, "尝试删除空单词");
            return false;
        }
        
        // 处理单词以确保一致性
        word = word.trim().toLowerCase();
        
        SQLiteDatabase db = this.getWritableDatabase();
        int result = db.delete(TABLE_WORDS, KEY_WORD + "=?", new String[]{word});
        
        if (result > 0) {
            Log.d(TAG, "单词已删除: " + word);
            return true;
        } else {
            Log.d(TAG, "单词不存在，无需删除: " + word);
            return false;
        }
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
        String query = "SELECT COUNT(*) FROM " + TABLE_WORDS + " WHERE " + KEY_WORD + " = ?";
        Cursor cursor = null;
        
        try {
            cursor = db.rawQuery(query, new String[]{word});
            if (cursor.moveToFirst()) {
                int count = cursor.getInt(0);
                return count > 0;
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "检查单词出错: " + e.getMessage());
            return false;
        } finally {
            if (cursor != null && !cursor.isClosed()) {
                cursor.close();
            }
        }
    }
    
    /**
     * 获取所有学习中的单词
     * @return 单词列表
     */
    public List<String> getAllLearningWords() {
        List<String> wordList = new ArrayList<>();
        
        String selectQuery = "SELECT " + KEY_WORD + " FROM " + TABLE_WORDS + 
                             " ORDER BY " + KEY_ID + " DESC";
        
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = null;
        
        try {
            cursor = db.rawQuery(selectQuery, null);
            
            if (cursor.moveToFirst()) {
                do {
                    String word = cursor.getString(0);
                    wordList.add(word);
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e(TAG, "获取单词列表出错: " + e.getMessage());
        } finally {
            if (cursor != null && !cursor.isClosed()) {
                cursor.close();
            }
        }
        
        return wordList;
    }
    
    /**
     * 切换单词的学习状态
     * 如果单词不在学习列表中，则添加
     * 如果单词已在学习列表中，则删除
     * @param word 要切换状态的单词
     * @return 切换后的状态，true表示"学习中"，false表示"已取消"
     */
    public boolean toggleWordLearningStatus(String word) {
        if (isWordInLearningList(word)) {
            removeWord(word);
            return false; // 已取消学习
        } else {
            addWord(word);
            return true; // 加入学习
        }
    }
    
    /**
     * 获取数据库中的单词数量
     * @return 单词数量
     */
    public int getWordCount() {
        String countQuery = "SELECT COUNT(*) FROM " + TABLE_WORDS;
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = null;
        
        try {
            cursor = db.rawQuery(countQuery, null);
            if (cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
            return 0;
        } catch (Exception e) {
            Log.e(TAG, "获取单词数量出错: " + e.getMessage());
            return 0;
        } finally {
            if (cursor != null && !cursor.isClosed()) {
                cursor.close();
            }
        }
    }
} 