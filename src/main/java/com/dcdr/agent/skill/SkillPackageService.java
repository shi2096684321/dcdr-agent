package com.dcdr.agent.skill;

import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.dto.SkillDtos;
import com.dcdr.agent.entity.SkillDefinitionEntity;
import com.dcdr.agent.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 技能包导入/导出 (V3.1 专家体系) —— 对应规划里的"远程模板市场"传输层。
 * <p>
 * <b>包格式</b>: 一个 JSON 对象({@link SkillDtos.SkillPackage}), 含元数据 + 指令正文 +
 * 内联的参考资源(路径→文本)。
 * <p>
 * <b>为什么不用 zip</b>:
 * <ol>
 *   <li>技能是纯文本能力包(不需要二进制), 单个 JSON 就可读、可 diff、可直接放进 git/Gist 仓库,
 *       天然满足"远程仓库 = 静态 JSON 清单 + 包 URL"的设计(见 V3.1 规划 §8.1);</li>
 *   <li>省掉 zip 带来的一整类边界问题: 路径穿越、编码、解压炸弹、大小校验时机;</li>
 *   <li>这条红利是"技能不携带可执行内容"换来的 —— 一旦允许带脚本, 就必须回到
 *       打包 + 审核 + 沙箱(本项目无审核环节, 故从设计上不支持)。</li>
 * </ol>
 * <p>
 * <b>导入语义</b>: 同名技能默认<b>拒绝</b>而非覆盖 —— 导入是"别人的技能包", 静默覆盖
 * 会改掉用户正在用的技能且无感知。需要覆盖必须显式传 {@code overwrite=true}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillPackageService {

    private final SkillService skillService;
    private final SkillRepository repository;

    /**
     * 从技能包导入(导入后技能为启用状态)。
     *
     * @param req    技能包 + 是否覆盖
     * @param userId 导入者(成为技能归属者)
     */
    @Transactional
    public SkillDtos.ImportResult importPackage(SkillDtos.ImportRequest req, String userId) {
        return importPackage(req, userId, true);
    }

    /**
     * 从技能包导入, 并指定导入后的启用状态。
     * <p>
     * <b>为什么需要这个重载</b>: 技能市场引入时拿不到技能正文(只有元数据骨架), 这种"半成品"
     * 必须<b>以停用状态入库</b> —— 否则一个没有正文的技能会进入模型可见的技能目录, 白占上下文预算。
     * 把启用状态放在<b>同一个事务</b>里决定, 而不是"先导入、再调一次启停接口": 后者是两个事务,
     * 中间失败会留下一个已启用却只有占位正文的技能。
     *
     * @param enabled 导入后是否启用; 手工导入恒为 true, 市场引入传 false
     */
    @Transactional
    public SkillDtos.ImportResult importPackage(SkillDtos.ImportRequest req, String userId, boolean enabled) {
        SkillDtos.SkillPackage pkg = req != null ? req.getSkill() : null;
        if (pkg == null) {
            throw new BusinessException(400, "技能包为空: 请在 body 的 skill 字段中提供包内容", "SKILL_PACKAGE_EMPTY");
        }
        if (pkg.getSchema() != null && !pkg.getSchema().isBlank()
                && !pkg.getSchema().startsWith("dcdr-skill/")) {
            throw new BusinessException(400, "不支持的技能包格式: " + pkg.getSchema(), "SKILL_PACKAGE_SCHEMA");
        }
        if (pkg.getInstructions() == null || pkg.getInstructions().isBlank()) {
            throw new BusinessException(400, "技能包缺少 instructions(技能指令正文)", "SKILL_PACKAGE_INVALID");
        }
        String name = pkg.getName() == null ? "" : pkg.getName().trim();
        if (name.isEmpty()) {
            throw new BusinessException(400, "技能包缺少 name(技能标识)", "SKILL_PACKAGE_INVALID");
        }

        SkillDtos.SaveSkillRequest save = toSaveRequest(pkg);
        // 复用 SkillService 的全部校验(名称格式/指令长度/资源路径白名单/配额), 保证
        // "导入"与"手工创建"走同一套规则 —— 两条路径各自校验必然会漂移
        boolean exists = repository.findByUserIdAndName(userId, name).isPresent();
        List<String> warnings = new ArrayList<>();

        if (exists && !Boolean.TRUE.equals(req.getOverwrite())) {
            throw new BusinessException(409,
                    "已存在同名技能: " + name + " (如需覆盖请传 overwrite=true, 否则请先改名或删除)",
                    "SKILL_EXISTS");
        }

        SkillDtos.SkillView view;
        String action;
        if (exists) {
            SkillDefinitionEntity existing = repository.findByUserIdAndName(userId, name).orElseThrow();
            view = skillService.update(existing.getId(), save, userId, false);
            action = "overwritten";
        } else {
            view = skillService.create(save, userId);
            action = "created";
        }

        // 血缘与导入同事务写入(走专用入口, 见 SkillService#markOrigin)。
        // 传空值表示"不改": 覆盖导入一个没带血缘的包时, 不该把原有的血缘抹掉。
        // 不往 view 上回写 —— 那是调用方读不到的中间量, 留着一个可能过期的副本只会误导后来者
        skillService.markOrigin(view.getId(), pkg.getSourceRef());

        // 依赖工具缺失只告警不阻断: 技能的知识部分独立可用, 缺一个工具就拒绝安装会让
        // "先装技能、后装插件"这种正常顺序变得不可行
        if (view.getMissingTools() != null && !view.getMissingTools().isEmpty()) {
            warnings.add("以下依赖工具当前未注册, 相关能力暂不可用: " + String.join(", ", view.getMissingTools()));
        }
        if (view.isHasExecutable()) {
            warnings.add("该技能声明需要可执行能力: 请确认沙箱相关工具已启用(技能包本身不含任何代码)");
        }

        // 以停用状态入库(技能市场的骨架正文走这条)。与导入同事务: 若这里失败则整体回滚,
        // 不会留下"已启用但正文是占位"的技能
        if (!enabled) {
            skillService.setEnabled(view.getId(), false, userId, false);
            warnings.add("已以停用状态导入: 请补全内容后手工启用, 否则该技能不会进入技能目录、不会生效");
        }

        SkillDtos.ImportResult result = new SkillDtos.ImportResult();
        result.setId(view.getId());
        result.setName(view.getName());
        result.setAction(action);
        result.setWarnings(warnings);
        log.info("技能包导入: name={}, action={}, user={}, enabled={}, 告警 {} 条",
                name, action, userId, enabled, warnings.size());
        return result;
    }

    /** 导出技能包(归属校验在读路径完成) */
    public SkillDtos.SkillPackage exportPackage(String id, String userId, boolean isAdmin) {
        SkillDtos.SkillView detail = skillService.get(id, userId, isAdmin);
        SkillDtos.SkillPackage pkg = new SkillDtos.SkillPackage();
        pkg.setName(detail.getName());
        pkg.setDisplayName(detail.getDisplayName());
        pkg.setIconUrl(detail.getIconUrl());
        pkg.setSourceUrl(detail.getSourceUrl());
        // 血缘随包传递: 下一位使用者拿到的不只是内容, 还有"它原本从哪来", 出问题能回到源头核对
        pkg.setSourceRef(detail.getSourceRef());
        pkg.setDescription(detail.getDescription());
        pkg.setVersion(detail.getVersion());
        pkg.setAuthor(detail.getAuthor());
        pkg.setCategoryId(detail.getCategoryId());
        pkg.setTags(detail.getTags());
        pkg.setInstructions(detail.getInstructions());
        pkg.setResources(detail.getResources() == null ? new LinkedHashMap<>() : detail.getResources());
        pkg.setRequiredTools(detail.getRequiredTools());
        pkg.setHasExecutable(detail.isHasExecutable());
        return pkg;
    }

    private SkillDtos.SaveSkillRequest toSaveRequest(SkillDtos.SkillPackage p) {
        SkillDtos.SaveSkillRequest save = new SkillDtos.SaveSkillRequest();
        save.setName(p.getName());
        save.setDisplayName(p.getDisplayName());
        save.setIconUrl(p.getIconUrl());
        save.setSourceUrl(p.getSourceUrl());
        save.setDescription(p.getDescription());
        save.setVersion(p.getVersion());
        save.setAuthor(p.getAuthor());
        save.setCategoryId(p.getCategoryId());
        save.setTags(p.getTags());
        save.setInstructions(p.getInstructions());
        save.setResources(p.getResources());
        save.setRequiredTools(p.getRequiredTools());
        save.setHasExecutable(p.getHasExecutable());
        // 导入的技能默认私有: "公开"是分发决策, 应由导入者显式决定, 不该由包内容替你决定
        save.setIsPublic(false);
        return save;
    }
}
