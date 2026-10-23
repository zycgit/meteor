struct Pair {
    int left;
    int right;
};

program Points {
    version VersionOne {
        Pair echo(Pair) = 1;
        int subtract(Pair) = 2;
    } = 1;
} = 0x20001111;
