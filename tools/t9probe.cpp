// 九鍵驗證探針：用本機 librime 直接餵數字，看會不會出中文候選。
// 編譯：g++ -O0 -o /tmp/rime-t9/t9probe /tmp/rime-t9/t9probe.cpp -lrime
#include <rime_api.h>
#include <cstdio>
#include <cstring>
#include <string>

static void feed(RimeApi* api, RimeSessionId s, const char* digits) {
  api->clear_composition(s);
  for (const char* p = digits; *p; ++p) {
    // mask=0：沒有修飾鍵；keycode 直接用 ASCII 數字元
    api->process_key(s, static_cast<int>(*p), 0);
  }
  RIME_STRUCT(RimeContext, ctx);
  if (!api->get_context(s, &ctx)) {
    printf("  輸入 %s → 拿不到 context\n", digits);
    return;
  }
  printf("  %s → 編碼「%s」%d 筆候選：",
         digits,
         ctx.composition.preedit ? ctx.composition.preedit : "",
         ctx.menu.num_candidates);
  for (int i = 0; i < ctx.menu.num_candidates && i < 9; ++i) {
    const char* text = ctx.menu.candidates[i].text ? ctx.menu.candidates[i].text : "?";
    const char* note = ctx.menu.candidates[i].comment;
    printf("%s%s%s ", text, note && *note ? "〈" : "", note && *note ? note : "");
    if (note && *note) printf("> ");
  }
  if (!ctx.menu.num_candidates) printf("（空）");
  printf("\n");
  api->free_context(&ctx);
}

int main(int argc, char** argv) {
  RimeApi* api = rime_get_api();
  if (!api) { printf("拿不到 rime api\n"); return 1; }
  RimeTraits traits{};
  std::memset(&traits, 0, sizeof(traits));
  RIME_STRUCT_INIT(RimeTraits, traits);
  traits.shared_data_dir = const_cast<char*>("/tmp/rime-t9/shared");
  traits.prebuilt_data_dir = const_cast<char*>("/tmp/rime-t9/build");
  traits.staging_dir = const_cast<char*>("/tmp/rime-t9/build");
  traits.user_data_dir = const_cast<char*>("/tmp/rime-t9/user");
  traits.distribution_name = const_cast<char*>("t9probe");
  traits.distribution_code_name = const_cast<char*>("t9probe");
  traits.distribution_version = const_cast<char*>("0.1");
  traits.app_name = const_cast<char*>("t9probe");
  api->setup(&traits);
  api->initialize(&traits);

  const char* schema = argc > 1 ? argv[1] : "t9_pinyin";
  RimeSessionId s = api->create_session();
  if (!s) { printf("建不了 session\n"); return 1; }
  if (!api->select_schema(s, schema)) {
    printf("選不到方案 %s\n", schema);
    return 1;
  }
  char current[256] = {0};
  api->get_current_schema(s, current, sizeof(current));
  printf("目前方案：%s\n\n", current);

  // T9 對照：2=abc 3=def 4=ghi 5=jkl 6=mno 7=pqrs 8=tuv 9=wxyz
  feed(api, s, "426");      // hao → 好
  feed(api, s, "64");       // ni  → 你
  feed(api, s, "26466");    // 多碼序列
  feed(api, s, "943426");   // 更長：看會不會給詞
  feed(api, s, "48626426"); // 更長的詞組
  api->destroy_session(s);
  api->finalize();
  return 0;
}
