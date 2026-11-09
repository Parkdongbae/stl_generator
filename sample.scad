// STL Generator 동작 확인용 예제 (module 방식)
// 부재 3개: base, pillar, ring

module base() {
    cube([30, 30, 5], center = true);
}

// 주석 속 "module" 단어는 부재 목록에 포함되면 안 됨: module fake_not_a_part();
module pillar() {
    translate([0, 0, 12]) cylinder(h = 20, d = 8);
}

module ring() {
    translate([-25, 0, 10])
    difference() {
        cylinder(h = 10, d = 16);
        cylinder(h = 12, d = 10);
    }
}
