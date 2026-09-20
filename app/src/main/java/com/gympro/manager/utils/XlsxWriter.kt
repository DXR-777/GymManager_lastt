package com.gympro.manager.utils

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * كاتب ملفات .xlsx خفيف بلا أي مكتبة خارجية (لا Apache POI، غير مناسبة لأندرويد
 * أصلاً بسبب اعتمادها على java.awt/javax.xml غير المتوفرة بالكامل هنا). يبني ملف
 * Excel صالحاً يدوياً (OOXML/SpreadsheetML مضغوط بصيغة ZIP قياسية) بأقل تعقيد ممكن:
 * ورقة واحدة، نصوص Inline (بلا sharedStrings.xml منفصل)، وتنسيق عريض اختياري لكل خلية.
 *
 * يدعم النصوص العربية RTL (ضبط sheetView rightToLeft="1") والأرقام كخلايا رقمية
 * فعلية (لا نصوص) كي تعمل عليها صيغ Excel لاحقاً لو احتاجها صاحب النادي.
 */
object XlsxWriter {

    data class Cell(
        val text: String,
        val bold: Boolean = false,
        /** إن مُرِّرت، تُكتب الخلية كرقم فعلي (بدون رمز عملة) بدل نص. */
        val numericValue: Double? = null
    )

    /**
     * يكتب [rows] (كل عنصر صفّ من [Cell]) إلى ملف xlsx في [outFile].
     * [sheetName] اسم التبويب داخل Excel (بلا أحرف خاصة ممنوعة، بحد أقصى 31 حرفاً).
     * [columnWidths] عرض تقريبي لكل عمود (بوحدة أحرف)، اختياري.
     */
    fun write(outFile: File, sheetName: String, rows: List<List<Cell>>, columnWidths: List<Int> = emptyList()) {
        val safeSheetName = sheetName.take(31).ifBlank { "Sheet1" }
        ZipOutputStream(outFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(contentTypesXml().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(rootRelsXml().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("xl/workbook.xml"))
            zip.write(workbookXml(safeSheetName).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels"))
            zip.write(workbookRelsXml().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("xl/styles.xml"))
            zip.write(stylesXml().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write(sheetXml(rows, columnWidths).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    private fun contentTypesXml() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
            <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
            <Default Extension="xml" ContentType="application/xml"/>
            <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
            <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
            <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
        </Types>
    """.trimIndent()

    private fun rootRelsXml() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
        </Relationships>
    """.trimIndent()

    private fun workbookXml(sheetName: String) = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
            <sheets>
                <sheet name="${xmlEscape(sheetName)}" sheetId="1" r:id="rId1"/>
            </sheets>
        </workbook>
    """.trimIndent()

    private fun workbookRelsXml() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
            <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
        </Relationships>
    """.trimIndent()

    /** فهرس 1 = افتراضي (غير عريض)، فهرس 2 = عريض. مرجَّع من [cellXfIndex]. */
    private fun stylesXml() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
            <fonts count="2">
                <font><sz val="11"/><name val="Calibri"/></font>
                <font><sz val="12"/><name val="Calibri"/><b/></font>
            </fonts>
            <fills count="1"><fill><patternFill patternType="none"/></fill></fills>
            <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
            <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
            <cellXfs count="3">
                <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
                <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyFont="1"/>
                <xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>
            </cellXfs>
        </styleSheet>
    """.trimIndent()

    private fun cellXfIndex(bold: Boolean) = if (bold) 2 else 0

    private fun sheetXml(rows: List<List<Cell>>, columnWidths: List<Int>): String {
        val colsXml = if (columnWidths.isEmpty()) "" else buildString {
            append("<cols>")
            columnWidths.forEachIndexed { index, width ->
                append("<col min=\"${index + 1}\" max=\"${index + 1}\" width=\"$width\" customWidth=\"1\"/>")
            }
            append("</cols>")
        }
        val rowsXml = buildString {
            rows.forEachIndexed { rowIndex, row ->
                val rowNum = rowIndex + 1
                append("<row r=\"$rowNum\">")
                row.forEachIndexed { colIndex, cell ->
                    val ref = "${columnLetter(colIndex)}$rowNum"
                    val style = cellXfIndex(cell.bold)
                    if (cell.numericValue != null) {
                        append("<c r=\"$ref\" s=\"$style\"><v>${cell.numericValue}</v></c>")
                    } else {
                        append("<c r=\"$ref\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xmlEscape(cell.text)}</t></is></c>")
                    }
                }
                append("</row>")
            }
        }
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetViews>
                    <sheetView rightToLeft="1" workbookViewId="0"/>
                </sheetViews>
                $colsXml
                <sheetData>$rowsXml</sheetData>
            </worksheet>
        """.trimIndent()
    }

    private fun columnLetter(index: Int): String {
        var i = index
        val sb = StringBuilder()
        while (i >= 0) {
            sb.insert(0, ('A' + (i % 26)))
            i = i / 26 - 1
        }
        return sb.toString()
    }

    private fun xmlEscape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
