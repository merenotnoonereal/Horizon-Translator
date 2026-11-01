/// Java's `Iterator` from `collection.iterator()`: a copy of the items taken when the loop starts, handed out by
/// `next()` until `hasNext()` is false.
#[derive(Clone, Debug)]
pub struct JIter<T> {
    items: Vec<T>,
    pos: usize,
}

impl<T: Clone> JIter<T> {
    pub fn new(items: Vec<T>) -> Self {
        JIter { items, pos: 0 }
    }

    pub fn has_next(&self) -> bool {
        self.pos < self.items.len()
    }

    /// `next()`: Java throws NoSuchElementException past the end; Rust panics.
    pub fn next(&mut self) -> T {
        let v = self.items.get(self.pos).cloned().expect("NoSuchElementException");
        self.pos += 1;
        v
    }
}

#[cfg(test)]
mod jiter_tests {
    use super::*;

    #[test]
    fn walks_in_order() {
        let mut it = JIter::new(vec![1, 2]);
        assert!(it.has_next());
        assert_eq!(it.next(), 1);
        assert_eq!(it.next(), 2);
        assert!(!it.has_next());
    }
}
