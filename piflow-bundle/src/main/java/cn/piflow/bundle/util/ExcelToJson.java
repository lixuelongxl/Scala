package cn.piflow.bundle.util;


import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.poi.hssf.usermodel.HSSFDateUtil;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class ExcelToJson {

//    public static final String XLSX = ".xlsx";
//    public static final String XLS=".xls";

    public static final Configuration configuration = new Configuration();


    public static net.sf.json.JSONArray readExcel(String pathStr,String hdfsUrl) throws IOException {

        configuration.set("fs.defaultFS",hdfsUrl);

        if (pathStr.endsWith(".xlsx")) {
            return readXLSX(pathStr);
        }else   {
            return readXLS(pathStr);
        }

//        return new net.sf.json.JSONArray();
    }


    public static net.sf.json.JSONArray readXLSX(String pathStr) throws  IOException{

        FileSystem fs = FileSystem.get(configuration);
        FSDataInputStream fdis = fs.open(new Path(pathStr));

        System.out.println("xlsx");

        Workbook book = new XSSFWorkbook(fdis);
        Sheet sheet = book.getSheetAt(0);
        return read(sheet, book);
    }


    public static net.sf.json.JSONArray readXLS(String pathStr) throws  IOException{

        FileSystem fs = FileSystem.get(configuration);
        FSDataInputStream fdis = fs.open(new Path(pathStr));

        System.out.println("xls");

        POIFSFileSystem poifsFileSystem = new POIFSFileSystem(fdis);
        Workbook book = new HSSFWorkbook(poifsFileSystem);
        Sheet sheet = book.getSheetAt(0);
        return read(sheet, book);
    }

    public static net.sf.json.JSONArray read(Sheet sheet, Workbook book) throws IOException{
        int rowStart = sheet.getFirstRowNum();	// ????????????
        int rowEnd = sheet.getLastRowNum();	// ????????????
        // ????????????????????????????????????????????????????????????????????????
        if (rowStart == rowEnd) {
            book.close();
            return new net.sf.json.JSONArray();
        }
        // ???????????????JSON?????????
        Row firstRow = sheet.getRow(rowStart);
        int cellStart = firstRow.getFirstCellNum();
        int cellEnd = firstRow.getLastCellNum();
        Map<Integer, String> keyMap = new HashMap<Integer, String>();
        for (int j = cellStart; j < cellEnd; j++) {
            keyMap.put(j,getValue(firstRow.getCell(j), rowStart, j, book, true).toLowerCase().replaceAll(" ","_"));
        }

        // ????????????JSON????????????
        net.sf.json.JSONArray array = new net.sf.json.JSONArray();
        for(int i = rowStart+1; i <= rowEnd ; i++) {
            Row eachRow = sheet.getRow(i);
            net.sf.json.JSONObject obj = new net.sf.json.JSONObject();
            StringBuffer sb = new StringBuffer();
            for (int k = cellStart; k < cellEnd; k++) {
                if (eachRow != null) {
                    String val = getValue(eachRow.getCell(k), i, k, book, false);
                    sb.append(val);		// ????????????????????????????????????????????????????????????
                    obj.put(keyMap.get(k),val);
                }
            }
            if (sb.toString().length() > 0) {
                array.add(obj);
            }
        }
        book.close();
        return array;
    }

    /**
     * ??????????????????????????????
     * @param cell ???????????????
     * @param rowNum ?????????
     * @param index ???????????????
     * @param book ?????????????????????
     * @param isKey ???????????????true-??????false-????????? ????????????Json???????????????????????????????????????Json????????????????????????
     * @return
     * @throws IOException
     */
    public static String getValue(Cell cell, int rowNum, int index, Workbook book, boolean isKey) throws IOException{

        // ????????????
        if (cell == null || cell.getCellType()==Cell.CELL_TYPE_BLANK ) {
            if (isKey) {
                book.close();
                throw new NullPointerException(String.format("the key on row %s index %s is null ", ++rowNum,++index));
            }else{
                return "";
            }
        }
        // 0. ?????? ??????
        if (cell.getCellType() == Cell.CELL_TYPE_NUMERIC) {
            if (HSSFDateUtil.isCellDateFormatted(cell)) {
                Date date = cell.getDateCellValue();
                DateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                return df.format(date);
            }
            String val = cell.getNumericCellValue()+"";
            val = val.toUpperCase();
            if (val.contains("E")) {
                val = val.split("E")[0].replace(".", "");
            }
            return val;
        }
        // 1. String??????
        if (cell.getCellType() == Cell.CELL_TYPE_STRING) {
            String val = cell.getStringCellValue();
            if (val == null || val.trim().length()==0) {
                if (book != null) {
                    book.close();
                }
                return "";
            }
            return val.trim();
        }
        // 2. ?????? CELL_TYPE_FORMULA
        if (cell.getCellType() == Cell.CELL_TYPE_FORMULA) {
            return cell.getStringCellValue();
        }
        // 4. ????????? CELL_TYPE_BOOLEAN
        if (cell.getCellType() == Cell.CELL_TYPE_BOOLEAN) {
            return cell.getBooleanCellValue()+"";
        }
        // 5.	?????? CELL_TYPE_ERROR
        return "";
    }









}