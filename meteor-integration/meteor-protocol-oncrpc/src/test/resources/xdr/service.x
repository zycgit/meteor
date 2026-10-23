const TEXT_LIMIT = 16;
typedef string Message<TEXT_LIMIT>;
typedef opaque Digest[4];
typedef opaque Data<128>;

struct Pair {
    int left;
    int right;
};

enum Mode {
    NONE = 0,
    TEXT = 7,
    DATA = 42
};

union Choice switch (Mode kind) {
case TEXT:
    Message text;
case DATA:
    Data data;
default:
    void;
};

struct Holder {
    unsigned int count;
    Digest digest;
    Message *note;
    Choice value;
    int fixed[2];
    int variable<>;
};

program Example {
    version VersionOne {
        Pair echo(Pair) = 1;
        int subtract(Pair) = 2;
        unsigned int unsignedEcho(unsigned int) = 3;
        Holder details(Holder) = 4;
    } = 1;
} = 0x20001111;
