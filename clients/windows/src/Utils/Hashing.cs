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
/// 作用有三点：
///   1. 我们自己写入剪贴板的内容会被系统判定为"变更"，若不做处理
///      会导致无限回环（A→B→A→B…）。这里记下远端来源的哈希，
///      本地上报前先查一遍，命中就跳过。
///   2. 减少重复上传：同一段内容反复复制时只同步一次。
///   3. 启动时把"剪贴板里已有的内容"登记进来，使它永远不会被当成
///      一次新的复制操作上报 —— 用户开机后剪贴板里通常还留着
///      上次关机前复制的东西，那些属于"以往的操作"，不该同步。
/// </summary>
public sealed class HashCache
{
    private readonly int _capacity;
    private readonly LinkedList<string> _order = new();
    private readonly HashSet<string> _set = new(StringComparer.Ordinal);
    // 注意：这里不能用 System.Threading.Lock，那是 .NET 9 才引入的类型，
    // 本项目目标框架是 net8.0-windows，用它会直接编译不过（CS0246）。
    private readonly object _gate = new();

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

    /// <summary>
    /// 判断是否已存在。
    ///
    /// 这里不要改成"命中即移除"：Windows 对一次剪贴板写入常常会投递多次
    /// WM_CLIPBOARDUPDATE，若第一次就把哈希删掉，后续那次就会被当成新内容
    /// 重复上报（服务端虽会去重，但客户端不该产生这次多余的流量）。
    /// </summary>
    public bool Contains(string hash)
    {
        lock (_gate)
        {
            return _set.Contains(hash);
        }
    }

    /// <summary>
    /// 撤销一次登记。
    ///
    /// 场景：下行先登记 hash（必须早于写剪贴板，否则回环挡不住），
    /// 但随后下载或写入失败 —— 此时内容并没有真正"处理过"。
    /// 若不撤销，对端重发这份内容时会被 Contains 永久跳过，表现为"漏同步且无法自愈"。
    ///
    /// 只影响这一个 hash，不影响其他条目；不存在时是安全的空操作。
    /// </summary>
    public void Remove(string hash)
    {
        if (string.IsNullOrEmpty(hash)) return;

        lock (_gate)
        {
            if (!_set.Remove(hash)) return;
            _order.Remove(hash);
        }
    }

    /// <summary>清空缓存（重新配对待同步内容变化后调用）</summary>
    public void Clear()
    {
        lock (_gate)
        {
            _order.Clear();
            _set.Clear();
        }
    }
}
