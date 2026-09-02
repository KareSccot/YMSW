package com.wuxibio.care.common;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;

public class PageResult<T> {

    private List<T> records;
    private long total;
    private long page;
    private long size;

    public List<T> getRecords() { return records; }
    public long getTotal() { return total; }
    public long getPage() { return page; }
    public long getSize() { return size; }

    public static <T> PageResult<T> of(IPage<T> iPage) {
        PageResult<T> result = new PageResult<>();
        result.records = iPage.getRecords();
        result.total = iPage.getTotal();
        result.page = iPage.getCurrent();
        result.size = iPage.getSize();
        return result;
    }
}
