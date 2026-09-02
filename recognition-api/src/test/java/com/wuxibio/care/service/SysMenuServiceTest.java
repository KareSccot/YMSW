package com.wuxibio.care.service;

import com.wuxibio.care.entity.SysMenu;
import com.wuxibio.care.mapper.SysMenuMapper;
import com.wuxibio.care.mapper.SysRoleMenuMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SysMenuServiceTest {

    @Mock private SysMenuMapper menuMapper;
    @Mock private SysRoleMenuMapper roleMenuMapper;

    private SysMenuService service;

    @BeforeEach
    void setUp() {
        service = new SysMenuService(menuMapper, roleMenuMapper);
    }

    @Test
    void listAllReturnsRowsFromDatabaseWithoutSeedingDefaults() {
        SysMenu page = new SysMenu();
        page.setId(2L);
        page.setType("page");
        page.setPath("/templates");
        when(menuMapper.selectList(any())).thenReturn(List.of(page));

        List<SysMenu> result = service.listAll();

        assertEquals(1, result.size());
        assertEquals(2L, result.get(0).getId());
        verify(menuMapper, never()).insert(any(SysMenu.class));
    }
}
