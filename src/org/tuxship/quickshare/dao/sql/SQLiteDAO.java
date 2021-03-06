package org.tuxship.quickshare.dao.sql;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import org.tuxship.quickshare.dao.DAOService;
import org.tuxship.quickshare.dao.sql.SQLContract.FilesTable;
import org.tuxship.quickshare.dao.sql.SQLContract.ShareTable;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

public class SQLiteDAO extends DAOService {

	SQLHelper sqlHelper = new SQLHelper(SQLiteDAO.this);
	
	private String SHARE_TABLE_NAME = ShareTable.TABLE_NAME;
	private String FILES_TABLE_NAME = FilesTable.TABLE_NAME;

	@Override
	public String addShare(String name, List<String> files) {
		if(files.isEmpty() || name.equals("")) {
			Log.e(LOGTAG, "No sharename provided or empty file list, on adding a share.");
			return "";
		}
		
		SQLiteDatabase db = sqlHelper.getWritableDatabase();
		
		if(containsShare(db, name)) {
			Log.w(LOGTAG, "Won't add a share with a duplicate name");
			return "";
		}
		
		String token = createToken(files);

		/*
		 * Store Share Information
		 */
		ContentValues shareValues = new ContentValues();
		shareValues.put(ShareTable.COLUMN_SHARE_NAME, name);
		shareValues.put(ShareTable.COLUMN_SHARE_TOKEN, token);

		db.insert(SHARE_TABLE_NAME, null, shareValues);

		/*
		 * Store files that belong to the share
		 */
		for(String file : files) {
			ContentValues fileValues = new ContentValues();
			fileValues.put(FilesTable.COLUMN_SHARE_NAME, name);
			fileValues.put(FilesTable.COLUMN_FILE, file);

			db.insert(FILES_TABLE_NAME, null, fileValues);
		}

		db.close();

		return token;
	}

	@Override
	public boolean removeShare(String share) {
		SQLiteDatabase db = sqlHelper.getWritableDatabase();
		
		boolean success = true;
		
		/*
		 * Delete entry from ShareTable
		 */
		{
			String selection = "? LIKE ?";
			String[] selectionArgs = {
					ShareTable.COLUMN_SHARE_NAME,
					share
			};

			int deleted = db.delete(SHARE_TABLE_NAME, selection, selectionArgs);

			if(deleted < 1) success = false;
		}
		
		
		/*
		 * Delete files belonging to the share
		 */
		{
			String selection = "? LIKE ?";
			String[] selectionArgs = {
					FilesTable.COLUMN_SHARE_NAME,
					share
			};

			int deleted = db.delete(FILES_TABLE_NAME, selection, selectionArgs);
		
			if(deleted < 1) success = false;
		}
		
		
		db.close();
		
		return success;
	}

	@Override
	public List<String> getShares() {
		SQLiteDatabase db = sqlHelper.getReadableDatabase();

		String[] projection = { ShareTable.COLUMN_SHARE_NAME };

		Cursor c = db.query(
				SHARE_TABLE_NAME,
				projection,
				null,
				null,
				null,
				null,
				null);

		ArrayList<String> shares = new ArrayList<String>();

		c.moveToFirst();

		while(!c.isAfterLast()) {
			shares.add(c.getString(
					c.getColumnIndex(projection[0])));
			c.moveToNext();
		}

		c.close();
		db.close();

		return shares;
	}
	
	@Override
	public int getShareCount() {
		SQLiteDatabase db = sqlHelper.getReadableDatabase();
		
		return (int) DatabaseUtils.queryNumEntries(db, SHARE_TABLE_NAME);
	}

	@Override
	public List<String> getFiles(String identifier, int type) throws TokenNotFoundException, ShareNotFoundException {
		if(type == TYPE_TOKEN)
			identifier = getShareName(identifier);
		
		SQLiteDatabase db = sqlHelper.getReadableDatabase();

		String[] projection = { FilesTable.COLUMN_FILE };

		Cursor c = db.query(
				FILES_TABLE_NAME,
				projection,
				"? LIKE ?",
				new String[] { FilesTable.COLUMN_SHARE_NAME, identifier },
				null,
				null,
				null);
		
		ArrayList<String> files = new ArrayList<String>(c.getCount());
		
		while(!c.isAfterLast()) {
			files.add(c.getString(
					c.getColumnIndex(projection[0])));
			c.moveToNext();
		}
		
		c.close();
		db.close();
		
		return files;
	}

	@Override
	public String getToken(String share) throws ShareNotFoundException {
		SQLiteDatabase db = sqlHelper.getReadableDatabase();

		String[] projection = { ShareTable.COLUMN_SHARE_TOKEN };

		Cursor c = db.query(
				SHARE_TABLE_NAME,
				projection,
				"? = ?",
				new String[] { ShareTable.COLUMN_SHARE_NAME, share },
				null,
				null,
				null);
		
		if(c.getCount() > 1)
			Log.w(LOGTAG, "Multiple rows with share name " + share);
		else if(c.getCount() < 1) {
			Log.e(LOGTAG, "No rows with share name " + share);
			throw new ShareNotFoundException();
		}
		
		c.close();
		db.close();
		
		return c.getString(c.getColumnIndex(projection[0]));
	}

	public boolean backupAndClear() {
		SQLiteDatabase db = sqlHelper.getWritableDatabase();
		
		/*
		 * Create a copy of the tables
		 */ 
//		{
//			String sql = "CREATE OR REPLACE TABLE " + ShareTable.BACKUP_TABLE_NAME +
//					" AS SELECT * FROM " + ShareTable.TABLE_NAME;
//			db.execSQL(sql);
//		}
		
		sqlHelper.createCleanBackupTables(db);

		db.close();
		
		/*
		 * Switch to copied tables
		 */
		SHARE_TABLE_NAME = ShareTable.BACKUP_TABLE_NAME;
		FILES_TABLE_NAME = FilesTable.BACKUP_TABLE_NAME;
		
		return true;
	}
	
	public boolean restore() {
		SQLiteDatabase db = sqlHelper.getWritableDatabase();
		
		/*
		 * Remove backup tables
		 */
		db.execSQL("DROP TABLE IF EXISTS " + ShareTable.BACKUP_TABLE_NAME);
		db.execSQL("DROP TABLE IF EXISTS " + FilesTable.BACKUP_TABLE_NAME);
		
		db.close();
		
		/*
		 * Switch to original tables
		 */
		SHARE_TABLE_NAME = ShareTable.TABLE_NAME;
		FILES_TABLE_NAME = FilesTable.TABLE_NAME;
		
		return true;
	}
	
	private boolean containsShare(SQLiteDatabase db, String shareName) {
		Cursor c = db.query(
				SHARE_TABLE_NAME,
				new String[] { ShareTable.COLUMN_SHARE_NAME },
				"? = ?",
				new String[] { ShareTable.COLUMN_SHARE_NAME, shareName },
				null,
				null,
				null);
		boolean contains = c.getCount() >= 1;
		c.close();
		
		return contains;
	}
	
	private String getShareName(String token) throws TokenNotFoundException {
		SQLiteDatabase db = sqlHelper.getReadableDatabase();

		String[] projection = { ShareTable.COLUMN_SHARE_NAME };

		Cursor c = db.query(
				SHARE_TABLE_NAME,
				projection,
				"? = ?",
				new String[] { ShareTable.COLUMN_SHARE_TOKEN, token },
				null,
				null,
				null);
		
		if(c.getCount() > 1)
			Log.w(LOGTAG, "Multiple rows with token  " + token);
		else if(c.getCount() < 1) {
			Log.e(LOGTAG, "No rows with token " + token);
			throw new TokenNotFoundException();
		}
		
		c.close();
		db.close();
		
		return c.getString(c.getColumnIndex(projection[0]));
	}
	
	private static String createToken(List<String> files) {
		StringBuffer result = new StringBuffer();

		try {
			byte[] bytesOfMessage = files.toString().getBytes("UTF-8");

			MessageDigest md;
			md = MessageDigest.getInstance("SHA1");

			byte[] thedigest = md.digest(bytesOfMessage);

			for(byte b : thedigest) {
				if((0xff & b) < 0x10) {
					result.append("0" + Integer.toHexString(0xff & b));
				} else {
					result.append(Integer.toHexString(0xff & b));
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		return result.substring(result.length() - DAOService.TOKEN_LENGTH);
	}

}
