package org.smssecure.smssecure.contacts;

import android.database.MatrixCursor;

import java.util.ArrayList;

/**
 * A MatrixCursor-backed implementation that mirrors the original ArrayListCursor behaviour
 * without relying on deprecated CursorWindow pinning calls.
 */
@SuppressWarnings("rawtypes")
public class ArrayListCursor extends MatrixCursor {

    private static final String ID_COLUMN = "_id";

    public ArrayListCursor(String[] columnNames, ArrayList<ArrayList> rows) {
        super(ensureIdColumn(columnNames), rows.size());

        boolean hasIdColumn = hasIdColumn(columnNames);

        for (int i = 0; i < rows.size(); i++) {
            MatrixCursor.RowBuilder rowBuilder = newRow();
            ArrayList row = rows.get(i);

            for (Object value : row) {
                rowBuilder.add(value);
            }

            if (!hasIdColumn) {
                rowBuilder.add(i);
            }
        }
    }

    private static boolean hasIdColumn(String[] columnNames) {
        for (String columnName : columnNames) {
            if (ID_COLUMN.equalsIgnoreCase(columnName)) {
                return true;
            }
        }

        return false;
    }

    private static String[] ensureIdColumn(String[] columnNames) {
        if (hasIdColumn(columnNames)) {
            return columnNames;
        }

        String[] result = new String[columnNames.length + 1];
        System.arraycopy(columnNames, 0, result, 0, columnNames.length);
        result[columnNames.length] = ID_COLUMN;
        return result;
    }
}
