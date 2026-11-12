package com.melonhub.rbac.config;

import com.melonhub.rbac.dto.Requests;
import com.melonhub.rbac.service.RbacService;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(10)
public class DemoData implements ApplicationRunner {
    private final RbacService r;
    private final boolean enabled;
    public DemoData(RbacService r, @Value("${melonhub.seed-demo}") boolean enabled) { this.r = r; this.enabled = enabled; }

    @Override @Transactional public void run(ApplicationArguments args) {
        if (!enabled || !r.departments(null).isEmpty()) return;
        String[][] departments = {{"研发中心","engineering"},{"产品设计部","product"},{"市场运营部","marketing"},{"财务部","finance"},{"人力资源部","hr"},{"客户服务部","support"}};
        var departmentIds = new ArrayList<Long>();
        for (var d : departments) departmentIds.add(r.department(new Requests.Department(d[0], d[1]), null, "system"));
        String[][] apps = {{"项目协作平台","project","项目管理与团队协作"},{"客户关系管理","crm","客户资料与销售管理"},{"企业 OA","oa","内部办公与流程协同"},{"财务管理系统","finance","企业财务与费用管理"},{"人力资源系统","hr","员工与人事管理"},{"知识库","wiki","企业知识与文档管理"},{"数据分析平台","analytics","数据报表与分析"},{"工单系统","ticket","问题反馈与服务管理"},{"采购管理","purchase","采购与供应商协同"},{"资产管理","assets","固定资产管理"},{"合同管理","contract","企业合同管理"},{"仓储管理","warehouse","仓储与库存管理"},{"营销平台","campaign","营销活动管理"},{"客户服务平台","service","客户服务管理"},{"研发效能平台","devops","研发流程管理"},{"代码托管","git","代码与仓库管理"},{"质量管理","quality","质量与测试管理"},{"供应链平台","supply","供应链管理"},{"会议管理","meeting","会议与预约管理"},{"邮件平台","mail","企业邮件管理"},{"文档中心","docs","共享文档管理"},{"培训平台","learning","员工培训管理"},{"考勤系统","attendance","员工考勤管理"},{"门户平台","portal","企业内部工作门户"}};
        var applicationIds = new ArrayList<Long>();
        for (var a : apps) applicationIds.add(((Number) r.application(new Requests.Application(a[0], a[1], a[2]), "system").get("id")).longValue());
        long dept = departmentIds.get(0), other = departmentIds.get(1), app = applicationIds.get(0);
        String[][] people = {{"陈明","chenming","chenming@company.com"},{"林雨晴","linyuqing","linyuqing@company.com"},{"张宇","zhangyu","zhangyu@company.com"},{"王晓","wangxiao","wangxiao@company.com"},{"李静","lijing","lijing@company.com"},{"周远","zhouyuan","zhouyuan@company.com"},{"赵悦","zhaoyue","zhaoyue@company.com"},{"刘洋","liuyang","liuyang@company.com"}};
        var userIds = new ArrayList<Long>();
        for (var u : people) userIds.add(r.user(new Requests.User(u[0], u[1], u[2], List.of(dept, other)), null, "system"));
        String[][] perms = {{"查看项目","project:read","project","read"},{"创建项目","project:create","project","create"},{"编辑项目","project:update","project","update"},{"删除项目","project:delete","project","delete"},{"查看任务","task:read","task","read"},{"创建任务","task:create","task","create"},{"编辑任务","task:update","task","update"},{"删除任务","task:delete","task","delete"},{"查看成员","member:read","member","read"},{"管理成员","member:manage","member","manage"},{"查看报表","report:read","report","read"},{"导出报表","report:export","report","export"}};
        var ids = new ArrayList<Long>();
        for (var p : perms) ids.add(r.permission(new Requests.Permission(p[0], p[1], p[2], p[3]), null, dept, app, "system"));
        String[][] roleNames = {{"软件管理员","administrator","维护软件内全部资源与配置"},{"项目负责人","project_owner","负责项目规划、执行与成员协作"},{"开发人员","developer","参与项目研发与任务管理"},{"测试人员","tester","参与任务验证与质量管理"},{"只读成员","viewer","查看项目、任务和报表"}};
        var roleIds = new ArrayList<Long>();
        for (int i = 0; i < roleNames.length; i++) {
            var n = roleNames[i];
            long role = r.role(new Requests.Role(n[0], n[1], n[2]), null, dept, app, "system");
            roleIds.add(role);
            List<Long> granted = i == 0 ? ids : i == 1 ? ids.subList(0, 10)
                    : i == 2 ? List.of(ids.get(0), ids.get(4), ids.get(5), ids.get(6), ids.get(8))
                    : i == 3 ? List.of(ids.get(0), ids.get(4), ids.get(6), ids.get(10))
                    : List.of(ids.get(0), ids.get(4), ids.get(8), ids.get(10));
            r.rolePermissions(role, dept, app, granted, "system");
        }
        for (int i = 0; i < userIds.size(); i++) r.userRoles(userIds.get(i), dept, app, List.of(roleIds.get(i % roleIds.size())), "system");
        long p = r.permission(new Requests.Permission("查看项目", "project:read", "project", "read"), null, other, app, "system");
        long role = r.role(new Requests.Role("只读成员", "viewer", "产品部门独立角色"), null, other, app, "system");
        r.rolePermissions(role, other, app, List.of(p), "system");
        r.userRoles(userIds.get(0), other, app, List.of(role), "system");
    }
}
