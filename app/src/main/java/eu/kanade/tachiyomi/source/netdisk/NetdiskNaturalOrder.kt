package eu.kanade.tachiyomi.source.netdisk

/**
 * SY 2026-10-08：名称自然序（数字段按数值比较，第2话<第10话）。
 * 原 115 Cookie 源 companion 内的工具，Cloud115Api 移除后提升为 netdisk 包公共工具
 * （115 开放平台源 / 夸克源共用）。
 */
fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var ie = i
            while (ie < a.length && a[ie].isDigit()) ie++
            var je = j
            while (je < b.length && b[je].isDigit()) je++
            val na = a.substring(i, ie).trimStart('0').ifBlank { "0" }
            val nb = b.substring(j, je).trimStart('0').ifBlank { "0" }
            val cmp = if (na.length != nb.length) na.length.compareTo(nb.length) else na.compareTo(nb)
            if (cmp != 0) return cmp
            i = ie
            j = je
        } else {
            val cmp = ca.compareTo(cb)
            if (cmp != 0) return cmp
            i++
            j++
        }
    }
    return (a.length - i).compareTo(b.length - j)
}
