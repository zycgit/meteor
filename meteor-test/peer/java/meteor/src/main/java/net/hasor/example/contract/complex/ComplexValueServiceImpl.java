/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.contract.complex;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 服务实际读取声明的嵌套类型并创建响应，收据证明请求已进入业务实现。 */
public class ComplexValueServiceImpl implements ComplexValueService {
    @Override
    public ComplexDocument acceptDocument(ComplexDocument request) {
        // 显式读取嵌套 Customer 和每条 Line，不能透传未经绑定的 Map 或原始报文。
        ComplexCustomer customer = new ComplexCustomer();
        customer.setCode(request.getCustomer().getCode());
        customer.setName(request.getCustomer().getName());
        customer.setVerified(request.getCustomer().isVerified());
        customer.setRating(request.getCustomer().getRating());
        List<ComplexLine> lines = new ArrayList<>();
        int quantity = 0;
        for (ComplexLine line : request.getLines()) {
            lines.add(this.copy(line));
            quantity += line.getQuantity();
        }

        // 保留全部业务字段与集合顺序，同时返回本次业务处理生成的收据。
        ComplexDocument result = new ComplexDocument();
        result.setDocumentId(request.getDocumentId());
        result.setCustomer(customer);
        result.setLines(lines);
        result.setRoute(new ArrayList<>(request.getRoute()));
        result.setReceipt("document:" + request.getDocumentId() + ":" + quantity);
        return result;
    }

    @Override
    public ComplexIndex acceptIndex(ComplexIndex request) {
        // 两种声明泛型都在服务端使用，DTO 值和 Integer 值不能退化为无类型内容。
        Map<String, ComplexLine> lines = new LinkedHashMap<>();
        for (Map.Entry<String, ComplexLine> entry : request.getLinesByCode().entrySet()) {
            lines.put(entry.getKey(), this.copy(entry.getValue()));
        }
        Map<String, Integer> stock = new LinkedHashMap<>();
        int quantity = 0;
        for (Map.Entry<String, Integer> entry : request.getStockByCode().entrySet()) {
            int count = entry.getValue();
            stock.put(entry.getKey(), count);
            quantity += count;
        }

        // 响应重新建立 Map，收据由服务端对解码后的 int32 值求和生成。
        ComplexIndex result = new ComplexIndex();
        result.setRevision(request.getRevision());
        result.setLinesByCode(lines);
        result.setStockByCode(stock);
        result.setReceipt("index:" + request.getRevision() + ":" + quantity);
        return result;
    }

    private ComplexLine copy(ComplexLine source) {
        ComplexLine result = new ComplexLine();
        result.setSku(source.getSku());
        result.setQuantity(source.getQuantity());
        result.setUnitPrice(source.getUnitPrice());
        return result;
    }
}
