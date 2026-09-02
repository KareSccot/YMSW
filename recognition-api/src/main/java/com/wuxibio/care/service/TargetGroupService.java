package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.entity.TargetGroup;
import com.wuxibio.care.entity.TargetGroupCondition;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.TargetGroupConditionMapper;
import com.wuxibio.care.mapper.TargetGroupMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class TargetGroupService {

    private final TargetGroupMapper groupMapper;
    private final TargetGroupConditionMapper conditionMapper;
    private final SysUserMapper userMapper;

    public TargetGroupService(TargetGroupMapper groupMapper, TargetGroupConditionMapper conditionMapper, SysUserMapper userMapper) {
        this.groupMapper = groupMapper;
        this.conditionMapper = conditionMapper;
        this.userMapper = userMapper;
    }

    public List<TargetGroup> listAll() {
        return groupMapper.selectList(new LambdaQueryWrapper<TargetGroup>().orderByAsc(TargetGroup::getId));
    }

    public TargetGroup getById(Long id) {
        TargetGroup tg = groupMapper.selectById(id);
        if (tg == null) throw new BizException("Target Group 不存在");
        return tg;
    }

    public List<TargetGroupCondition> getConditions(Long groupId) {
        return conditionMapper.selectList(
                new LambdaQueryWrapper<TargetGroupCondition>().eq(TargetGroupCondition::getTargetGroupId, groupId));
    }

    @Transactional
    public void create(TargetGroup group, List<TargetGroupCondition> conditions) {
        groupMapper.insert(group);
        if (conditions != null) {
            for (TargetGroupCondition c : conditions) {
                c.setTargetGroupId(group.getId());
                conditionMapper.insert(c);
            }
        }
    }

    @Transactional
    public void update(Long id, TargetGroup group, List<TargetGroupCondition> conditions) {
        TargetGroup existing = groupMapper.selectById(id);
        if (existing == null) throw new BizException("Target Group 不存在");

        TargetGroup update = new TargetGroup();
        update.setId(id);
        if (group.getName() != null) update.setName(group.getName());
        if (group.getDescription() != null) update.setDescription(group.getDescription());
        groupMapper.updateById(update);

        if (conditions != null) {
            conditionMapper.delete(
                    new LambdaQueryWrapper<TargetGroupCondition>().eq(TargetGroupCondition::getTargetGroupId, id));
            for (TargetGroupCondition c : conditions) {
                c.setTargetGroupId(id);
                c.setId(null);
                conditionMapper.insert(c);
            }
        }
    }

    @Transactional
    public void delete(Long id) {
        conditionMapper.delete(
                new LambdaQueryWrapper<TargetGroupCondition>().eq(TargetGroupCondition::getTargetGroupId, id));
        groupMapper.deleteById(id);
    }

    /**
     * Get users matching the target group conditions.
     * Conditions are OR-ed: user matches if they match ANY condition.
     */
    public List<SysUser> getMembers(Long groupId) {
        List<TargetGroupCondition> conditions = getConditions(groupId);
        if (conditions.isEmpty()) return List.of();

        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> {
            for (int i = 0; i < conditions.size(); i++) {
                TargetGroupCondition c = conditions.get(i);
                if (i > 0) w.or();
                switch (c.getDimension()) {
                    case "department" -> w.eq(SysUser::getDepartment, c.getValue());
                    case "country" -> w.eq(SysUser::getCountry, c.getValue());
                    case "legal_entity" -> w.eq(SysUser::getCompanyName, c.getValue());
                    default -> w.eq(SysUser::getDepartment, c.getValue());
                }
            }
        });
        List<SysUser> users = userMapper.selectList(wrapper);
        users.forEach(u -> u.setPassword(null));
        return users;
    }
}
