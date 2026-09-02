package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.SysMenu;
import com.wuxibio.care.entity.SysRoleMenu;
import com.wuxibio.care.mapper.SysMenuMapper;
import com.wuxibio.care.mapper.SysRoleMenuMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SysMenuService {

    private final SysMenuMapper menuMapper;
    private final SysRoleMenuMapper roleMenuMapper;

    public SysMenuService(SysMenuMapper menuMapper, SysRoleMenuMapper roleMenuMapper) {
        this.menuMapper = menuMapper;
        this.roleMenuMapper = roleMenuMapper;
    }

    public List<SysMenu> listAll() {
        return menuMapper.selectList(
                new LambdaQueryWrapper<SysMenu>()
                        .orderByAsc(SysMenu::getSortOrder)
                        .orderByAsc(SysMenu::getId));
    }

    @Transactional
    public void create(SysMenu menu) {
        menuMapper.insert(menu);
    }

    @Transactional
    public void update(Long id, SysMenu menu) {
        SysMenu existing = menuMapper.selectById(id);
        if (existing == null) throw new BizException("菜单不存在");

        SysMenu update = new SysMenu();
        update.setId(id);
        if (menu.getParentId() != null) update.setParentId(menu.getParentId());
        if (menu.getName() != null) update.setName(menu.getName());
        if (menu.getType() != null) update.setType(menu.getType());
        if (menu.getPath() != null) update.setPath(menu.getPath());
        if (menu.getPermissionKey() != null) update.setPermissionKey(menu.getPermissionKey());
        if (menu.getSortOrder() != null) update.setSortOrder(menu.getSortOrder());
        menuMapper.updateById(update);
    }

    @Transactional
    public void delete(Long id) {
        Long childCount = menuMapper.selectCount(
                new LambdaQueryWrapper<SysMenu>().eq(SysMenu::getParentId, id));
        if (childCount > 0) {
            throw new BizException("该菜单下有子菜单，无法删除");
        }
        roleMenuMapper.delete(
                new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getMenuId, id));
        menuMapper.deleteById(id);
    }
}
