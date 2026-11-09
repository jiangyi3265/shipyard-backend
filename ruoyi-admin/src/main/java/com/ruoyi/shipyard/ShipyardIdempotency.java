package com.ruoyi.shipyard;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;
import static com.ruoyi.shipyard.ShipyardService.*;

/** Compare the business input, never current worker rates or client-supplied derived totals. */
final class ShipyardIdempotency {
    static final String META = "_requestIdentity";
    private ShipyardIdempotency() { }

    static void same(boolean matches) {
        if (!matches) throw new ServiceException("请求编号对应的内容已变更，请刷新核对原记录后重新发起", 409);
    }

    static Map<String,Object> identity(Map<String,Object> request, String resolvedDate) {
        return map("date", resolvedDate, "fingerprint", fingerprint(request, resolvedDate));
    }

    @SuppressWarnings("unchecked")
    static Map<String,Object> original(Map<String,Object> saved) {
        Map<String,Object> content = JSON.parseObject(String.valueOf(saved.get("body")));
        if (content.get(META) instanceof Map) return (Map<String,Object>) content.get(META);
        // Records written before request fingerprints already contain the accepted input.
        Map<String,Object> request = new LinkedHashMap<>(content);
        request.putAll(map("projectId", saved.get("project_id"), "areaId", saved.get("area_id"),
                "kind", saved.get("kind"), "type", saved.get("type"), "amount", saved.get("amount"),
                "date", str(saved,"business_date")));
        return identity(request, str(saved,"business_date"));
    }

    static void submission(Map<String,Object> request, Map<String,Object> saved) {
        Map<String,Object> accepted = original(saved);
        same(str(accepted,"fingerprint").equals(fingerprint(request, str(accepted,"date"))));
    }

    @SuppressWarnings("unchecked")
    static String fingerprint(Map<String,Object> request, String defaultDate) {
        String kind = required(request,"kind",12), type = str(request,"type");
        String day = str(request,"date");
        if (day.isEmpty()) day = defaultDate;
        if ("用工".equals(kind) && "包工".equals(type)) day = required(request,"start",10);
        Map<String,Object> normalized = map("kind", kind, "projectId", number(request.get("projectId")),
                "date", day, "note", required(request,"note",500));
        if (!"报销".equals(kind)) normalized.put("areaId", number(request.get("areaId")));
        Object uploads = request.get("files");
        check(uploads == null || uploads instanceof List, "凭证格式错误");
        List<String> files = new ArrayList<>();
        if (uploads != null) for (Object upload : (List<?>) uploads) files.add(String.valueOf(upload));
        Collections.sort(files);
        normalized.put("files", files);
        if ("用工".equals(kind)) {
            normalized.put("type", type);
            List<Long> workers = ids(request.get("people"));
            normalized.put("people", workers);
            if ("包工".equals(type) || "点工".equals(type)) {
                String field = "包工".equals(type) ? "allocations" : "hours";
                Map<String,Object> input = request.get(field) instanceof Map
                        ? (Map<String,Object>) request.get(field) : Collections.emptyMap();
                Map<String,Object> values = new LinkedHashMap<>();
                for (Long worker : workers) values.put(worker.toString(), decimal(input.get(worker.toString()),true).toPlainString());
                normalized.put(field, values);
            }
            if ("包工".equals(type)) {
                normalized.put("start", required(request,"start",10));
                normalized.put("end", required(request,"end",10));
                normalized.put("amount", decimal(request.get("amount"),true).toPlainString());
                // Only a non-default payroll month changes the fingerprint, so earlier retries still match.
                String payrollMonth = str(request,"payrollMonth"), start = required(request,"start",10);
                if (!payrollMonth.isEmpty() && !start.startsWith(payrollMonth)) normalized.put("payrollMonth", payrollMonth);
            } else if ("加班".equals(type)) {
                normalized.put("duration", decimal(request.get("duration"),true).toPlainString());
            }
        } else {
            normalized.put("amount", decimal(request.get("amount"),true).toPlainString());
            if ("报销".equals(kind)) normalized.put("category", required(request,"category",30));
            else normalized.put("person", number(request.get("person")));
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(JSON.toJSONString(normalized).getBytes(StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder(64);
            for (byte value : digest) hash.append(String.format("%02x", value & 0xff));
            return hash.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static void payroll(Map<String,Object> batch, long projectId, long actor, String month,
                        List<Long> selected, List<Long> paidLines) {
        List<Long> normalized = new ArrayList<>(paidLines);
        Collections.sort(normalized);
        same(number(batch.get("project_id")) == projectId && number(batch.get("paid_by")) == actor
                && month.equals(str(batch,"month")) && selected.equals(normalized));
    }

    static void ledger(Map<String,Object> entry, long projectId, long actor, String title,
                       BigDecimal amount, String day, String note) {
        same(number(entry.get("project_id")) == projectId && number(entry.get("created_by")) == actor
                && title.equals(str(entry,"title")) && amount.compareTo(new BigDecimal(str(entry,"amount"))) == 0
                && day.equals(str(entry,"business_date")) && note.equals(str(entry,"note")));
    }
}
