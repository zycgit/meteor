#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

static int intercepted_open(const char *path, int flags, ...);

// 测试直接编译真实执行器，仅在本编译单元截获读取进程身份的时机。
#define open intercepted_open
#define main native_main
#include "peer-process.c"
#undef main
#undef open

static bool race_armed;
static const char *race_scenario;
static char watched_identity[64];

static int intercepted_open(const char *path, int flags, ...) {
    if (race_armed && !strcmp(path, watched_identity)) {
        race_armed = false;

        // 控制器已读到 running；在身份查询前发布新快照并模拟原监督器消失。
        tree_empty = true;
        temporary_removed = true;
        timestamp(finished, sizeof(finished));
        const char *state = "exited";
        char *original_id = configuration.id;
        if (!strcmp(race_scenario, "failed")) {
            state = "failed";
            snprintf(failure, sizeof(failure), "Fixture cleanup failure");
        } else if (!strcmp(race_scenario, "wrong-id")) {
            configuration.id = "another-peer";
        } else if (!strcmp(race_scenario, "still-running")) {
            state = "running";
        } else if (!strcmp(race_scenario, "unclean")) {
            tree_empty = false;
        }
        int result = publish(state, tree_empty ? "" : "123");
        configuration.id = original_id;
        if (result) {
            perror("Cannot publish coordinated fixture status");
            _exit(2);
        }
        errno = ENOENT;
        return -1;
    }

    // 未命中的文件调用仍使用 libc open，保留原执行器的文件与锁行为。
    bool has_mode = flags & O_CREAT;
#ifdef O_TMPFILE
    has_mode |= (flags & O_TMPFILE) == O_TMPFILE;
#endif
    if (has_mode) {
        va_list arguments;
        va_start(arguments, flags);
        mode_t mode = va_arg(arguments, mode_t);
        va_end(arguments);
        return open(path, flags, mode);
    }
    return open(path, flags);
}

int main(int argc, char **argv) {
    if (argc != 3 || argv[1][0] != '/' || strlen(argv[1]) >= sizeof(evidence_directory)) {
        fprintf(stderr, "Usage: peer-process-race /absolute/launch.json scenario\n");
        return 2;
    }
    race_scenario = argv[2];
    bool known_scenario = !strcmp(race_scenario, "clean") || !strcmp(race_scenario, "failed")
            || !strcmp(race_scenario, "wrong-id") || !strcmp(race_scenario, "still-running")
            || !strcmp(race_scenario, "unclean");
    if (!known_scenario) {
        fprintf(stderr, "Unknown race scenario: %s\n", race_scenario);
        return 2;
    }

    // 只建立当前夹具的 launch 和身份，不启动业务进程或发送真实信号。
    strcpy(evidence_directory, argv[1]);
    char *separator = strrchr(evidence_directory, '/');
    if (separator == evidence_directory) separator[1] = 0;
    else *separator = 0;
    identity self;
    wrapper_pid = getpid();
    if (load_launch() || !process_identity(wrapper_pid, &self)) {
        perror("Cannot prepare fixture identity");
        return 2;
    }
    wrapper_ticks = self.ticks;
    timestamp(started, sizeof(started));
    if (publish("running", "")) {
        perror("Cannot publish initial fixture status");
        return 2;
    }
    snprintf(watched_identity, sizeof(watched_identity), "/proc/%ld/stat", (long)wrapper_pid);
    race_armed = true;

    // 执行真实停止逻辑；断言协调点确实经过，避免夹具绕过竞态路径。
    int result = stop_peer(false);
    if (race_armed) {
        fprintf(stderr, "Fixture did not reach the coordinated identity lookup\n");
        result = 2;
    }
    free(configuration.arguments);
    free_tokens(&configuration.input);
    return result;
}
