package com.wingtisky.forum;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Arrays;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构边界规则的强制检查（spec §4.2、architecture.md §3.1）。
 *
 * <p>这些规则不是"建议"——违反即测试失败。目的是让"三个域"的边界从
 * 文档里的一句话变成 CI 能拦住的硬约束：M9 物理拆分的收益，取决于
 * M0–M8 期间这条线有没有被守住。
 *
 * <p><b>本测试是第二道闸门。</b>第一道是 Maven 的模块依赖：子模块只能
 * 看到它在 POM 里声明过的模块，跨域 import 会直接编译失败。但 Maven
 * 拦不住"为了让代码编过，顺手在 POM 里加一条依赖"——那正是本测试存在
 * 的理由。两道闸门的关系见 {@code tools/README.md} 同类说明。
 *
 * <p><b>包名对应关系（改包名 = 规则失效，见 Task 1 Step 5）</b>：
 * <ul>
 *   <li>{@code com.wingtisky.forum.common}   —— wt-common</li>
 *   <li>{@code com.wingtisky.forum.domain}   —— wt-domain</li>
 *   <li>{@code com.wingtisky.forum.infra}    —— wt-infra</li>
 *   <li>{@code com.wingtisky.forum.forum..}  —— forum/** 内容域</li>
 *   <li>{@code com.wingtisky.forum.trade..}  —— trade/** 交易域</li>
 *   <li>{@code com.wingtisky.forum.seckill..}—— seckill/** 秒杀域</li>
 * </ul>
 */
class ArchitectureTest {

    /**
     * 三个业务域，两两互斥。这是本次架构的核心边界。
     *
     * <p>注意通配符：必须是 {@code ..forum.forum..} 而不是 {@code ..forum..}——
     * 后者会连 {@code com.wingtisky.forum.common} 一起圈进去（因为它也含
     * "forum" 这一段），从而报出大量假违规。
     */
    private static final String[] DOMAINS = {
            "..forum.forum..", "..forum.trade..", "..forum.seckill.."
    };

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.wingtisky.forum");
    }

    @Test
    @DisplayName("规则1：wt-common 不得依赖任何业务域")
    void commonShouldNotDependOnAnyDomain() {
        noClasses().that().resideInAPackage("..forum.common..")
                .should().dependOnClassesThat().resideInAnyPackage(DOMAINS)
                .because("wt-common 是最底层工具，依赖业务域会造成环")
                .check(classes);
    }

    @Test
    @DisplayName("规则2：三个业务域之间两两不得互相依赖")
    void domainsShouldNotDependOnEachOther() {
        for (String from : DOMAINS) {
            noClasses().that().resideInAPackage(from)
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            Arrays.stream(DOMAINS)
                                    .filter(to -> !to.equals(from))
                                    .toArray(String[]::new))
                    .because("域间必须通过 wt-domain 的契约接口通信，不得直接依赖——"
                            + "这条线是 M9 物理拆分能成立的前提")
                    .check(classes);
        }
    }

    @Test
    @DisplayName("规则3：wt-domain 不得依赖 wt-infra（契约不依赖实现）")
    void domainShouldNotDependOnInfra() {
        noClasses().that().resideInAPackage("..forum.domain..")
                .should().dependOnClassesThat().resideInAPackage("..forum.infra..")
                .because("wt-domain 只放契约，反向依赖基础设施实现会让契约被实现绑死")
                .check(classes);
    }

    @Test
    @DisplayName("规则4：wt-infra 不得依赖任何业务域")
    void infraShouldNotDependOnAnyDomain() {
        noClasses().that().resideInAPackage("..forum.infra..")
                .should().dependOnClassesThat().resideInAnyPackage(DOMAINS)
                .because("infra 放的是机制，机制一旦知道业务策略就不再是机制")
                .check(classes);
    }

    @Test
    @DisplayName("规则5：启动类只允许存在于 app 模块")
    void onlyAppShouldDeclareSpringBootApplication() {
        // M9 拆出 forum-app / trade-app / seckill-app 时，本规则的包名需要同步调整——
        // 那时它才真正开始发挥作用。在 M0 它是恒真的，作用是拦住"M1–M8 期间
        // 有人提前加第二个启动类（比如为了测试另起一个进程）"。
        ArchRuleDefinition.classes().that().areAnnotatedWith(SpringBootApplication.class)
                .should().resideInAPackage("com.wingtisky.forum")
                .because("M0–M8 只允许 app 一个进程；多出启动类意味着有人提前拆了进程")
                .check(classes);
    }
}
