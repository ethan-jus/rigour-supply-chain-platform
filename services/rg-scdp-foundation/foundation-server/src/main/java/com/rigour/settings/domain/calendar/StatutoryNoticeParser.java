package com.rigour.settings.domain.calendar;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;

/** Parse complete annual State Council notices; ambiguous or incomplete notices fail closed. */
public final class StatutoryNoticeParser {
    private static final List<String> HOLIDAYS =
            List.of("元旦", "春节", "清明节", "劳动节", "端午节", "中秋节", "国庆节");
    private static final Pattern SECTION = Pattern.compile("[一二三四五六七八九十]+、([^：:。]{1,30})[：:]");
    private static final Pattern DATE =
            Pattern.compile("(?:(\\d{4})年)?(?:(\\d{1,2})月)?(\\d{1,2})日");
    private static final Pattern COUNT = Pattern.compile("(?:共|放假)(\\d{1,2})天");

    private StatutoryNoticeParser() {}

    public static String title(int year) {
        return "国务院办公厅关于" + year + "年部分节假日安排的通知";
    }

    public static String compact(String text) {
        return text.replaceAll("[\\s\\p{Z}]+", "");
    }

    public static StatutorySourceYear parse(int year, String url, String text) {
        String body = compact(text);
        if (!body.contains(title(year)) || !body.contains("国务院办公厅") || !body.contains("国办发明电"))
            throw new IllegalArgumentException("公告标题、发文机关或文号不符");
        var sections = SECTION.matcher(body).results().toList();
        var covered = new HashSet<String>();
        var days = new TreeMap<LocalDate, StatutorySourceDay>();
        for (int i = 0; i < sections.size(); i++) {
            var header = sections.get(i);
            String name = header.group(1);
            String remainder = name;
            for (String holiday : HOLIDAYS) {
                if (name.contains(holiday)) covered.add(holiday);
                remainder = remainder.replace(holiday, "");
            }
            if (!remainder.replaceAll("[、,，和及]", "").isEmpty())
                throw new IllegalArgumentException("公告包含未识别的节假日段落");
            String section =
                    body.substring(
                            header.end(),
                            i + 1 < sections.size() ? sections.get(i + 1).start() : body.length());
            int off = section.indexOf("放假");
            if (off < 0) throw new IllegalArgumentException("公告缺少放假安排");
            var rest = dates(year, section.substring(0, off), true, name.contains("元旦"));
            var count = COUNT.matcher(section.substring(off).split("[。；;]", 2)[0]);
            if (!count.find() || rest.size() != Integer.parseInt(count.group(1)))
                throw new IllegalArgumentException("公告放假天数与日期范围不一致");
            for (var date : rest) put(days, name, date, true);
            for (String sentence : section.substring(off).split("[。；;]")) {
                int work = sentence.indexOf("上班");
                if (work < 0) continue;
                if (sentence.contains("放假")) throw new IllegalArgumentException("放假与补班句式无法明确分离");
                for (var date : dates(year, sentence.substring(0, work), false, false))
                    put(days, name, date, false);
            }
        }
        if (!covered.containsAll(HOLIDAYS)) throw new IllegalArgumentException("公告未覆盖完整年度节假日");
        var result = new StatutorySourceYear(year, List.of(url), List.copyOf(days.values()), body);
        StatutoryCalendarValidation.validate(year, result);
        return result;
    }

    private static void put(
            Map<LocalDate, StatutorySourceDay> result, String name, LocalDate date, boolean off) {
        if (result.putIfAbsent(date, new StatutorySourceDay(name, date, off)) != null)
            throw new IllegalArgumentException("公告日期重复或放假补班冲突");
    }

    private static List<LocalDate> dates(
            int year, String clause, boolean rangeRequired, boolean newYear) {
        String plain = clause.replaceAll("[（(][^）)]*[）)]", "");
        var matches = DATE.matcher(plain).results().toList();
        if (matches.isEmpty()) throw new IllegalArgumentException("公告未识别到日期");
        boolean range = plain.matches(".*日(?:至|到|—|-).*日.*");
        if (rangeRequired && matches.size() > 1 && !range || range && matches.size() != 2)
            throw new IllegalArgumentException("公告日期范围不明确");
        var values = new ArrayList<LocalDate>();
        int previousMonth = 0;
        int previousYear = year;
        for (int i = 0; i < matches.size(); i++) {
            var value = matches.get(i);
            int month = value.group(2) == null ? previousMonth : Integer.parseInt(value.group(2));
            int dateYear = value.group(1) == null ? previousYear : Integer.parseInt(value.group(1));
            if (i == 0 && newYear && month == 12 && value.group(1) == null) dateYear = year - 1;
            if (i > 0 && range && value.group(1) == null && month < previousMonth) dateYear++;
            values.add(LocalDate.of(dateYear, month, Integer.parseInt(value.group(3))));
            previousMonth = month;
            previousYear = dateYear;
        }
        if (!range) return values;
        var start = values.getFirst();
        var end = values.getLast();
        long length = ChronoUnit.DAYS.between(start, end) + 1;
        if (length < 1 || length > 15) throw new IllegalArgumentException("公告日期跨度异常");
        return start.datesUntil(end.plusDays(1)).toList();
    }
}
