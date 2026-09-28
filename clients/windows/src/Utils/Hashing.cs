using System.Security.Cryptography;
using System.Text;

namespace ClipBridge.Utils;

/// <summary>内容哈希与去重相关的工具</summary>
public static class Hashing
{
    /// <summary>计算文本的 SHA-256（小写十六进制）</summary>
    public static string Sha256Hex(string text)
        => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(text))).ToLowerInvariant();

    /// <summary>计算字节数组的 SHA-256（小写十六进制）</summary>
    public static string Sha256Hex(byte[] data)
        => Convert.ToHexString(SHA256.HashData(data)).ToLowerInvariant();
}

/// <summary>
/// 近期内容哈希集合。
///
/// 作用有两点：
///   1. 我们自己写入剪贴板的内容会被系统判定为"变更"，若不做处理
///      会导致无限回环（A→B→A→B…）。这里记下远端来源的哈希，
///      本地上报前先查一遍，命中就跳过。
///   2. 减少重复上传：同一段内容反复复制时只同步一次。
/// </summary>
public sealed class HashCache
{
    private readonly int _capacity;
    private readonly LinkedList<string> _order = new();
    private readonly HashSet<string> _set = new(StringComparer.Ordinal);
    private readonly Lock _gate = new();

    public HashCache(int capacity = 200)
    {
        _capacity = capacity;
    }

    /// <summary>加入一个哈希；超出容量时淘汰最早的</summary>
    public void Add(string hash)
    {
        if (string.IsNullOrEmpty(hash)) return;

        lock (_gate)
        {
            if (!_set.Add(hash)) return;

            _order.AddLast(hash);
            while (_order.Count > _capacity)
            {
                var oldest = _order.First!.Value;
                _order.RemoveFirst();
                _set.Remove(oldest);
            }
        }
    }

    /// <summary>判断是否已存在</summary>
    public bool Contains(string hash)
    {
        lock (_gate)
        {
            return _set.Contains(hash);
        }
    }

    /// <summary>消费式查询：命中则移除。用于"远端内容被本地写入后仅跳过这一次"的场景</summary>
    public bool Consume(string hash)
    {
        lock (_gate)
        {
            if (!_set.Remove(hash)) return false;
            _order.Remove(hash);
            return true;
        }
    }

    public void Clear()
    {
        lock (_gate)
        {
            _order.Clear();
            _set.Clear();
        }
    }
}
